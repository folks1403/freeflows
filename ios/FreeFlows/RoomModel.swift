import Foundation
import SwiftUI
import AVFoundation
import UIKit

@MainActor final class RoomModel: ObservableObject {
    @Published var roomLink = UserDefaults.standard.string(forKey: "roomLink") ?? ""
    @Published var name = UserDefaults.standard.string(forKey: "mcName") ?? ""
    @Published var compete = true
    @Published var consent = false
    @Published var room: RoomState?
    @Published var message = "Cole o convite atual da sala para começar."
    @Published var audioMessage = "Áudio desligado"
    @Published var transcriptionMessage = "Aguardando voz"
    @Published var busy = false
    @Published var audioReady = false
    @Published var level: Float = 0
    @Published var health: Health?
    private(set) var api: RoomAPI?
    private let audio = NativeAudio()
    private let relay = NativeRelay()
    private var pollTask: Task<Void,Never>?
    private var uploadTask: Task<Void,Never>?
    private var uploads: [AudioSegment] = []
    private var uploadBytes = 0
    private var segments = SpeechSegments()
    private var serverOffset = 0.0
    private var spokenTurn: String?
    private var melodyVersion: Double?
    private var loadingMelody = false
    private var wasPublishing = false
    private var backgrounded = false

    var me: Participant? { room?.members.first { $0.id == api?.session?.id } }
    var isCreator: Bool { !(api?.connection.hostToken.isEmpty ?? true) }
    var joined: Bool { api?.session != nil && room != nil }
    var available: Int { room?.members.filter { $0.role == "mc" && $0.connected && $0.audioReady == true && $0.simulated != true }.count ?? 0 }
    var nativeOnly: Bool { !(room?.members.contains { $0.role == "mc" && $0.connected && $0.audioReady == true && $0.simulated != true && $0.audioTransport != "native-pcm" } ?? false) }
    var invitation: String {
        guard let api else { return "" }; var c = URLComponents(url: api.connection.baseURL, resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "invite", value: api.connection.invite)]; return c.url!.absoluteString
    }
    var turnLabel: String {
        guard let room, let turn = room.turn, room.phase == "live" else { return room?.phase == "judging" ? "Analisando a batalha…" : "A arena te espera" }
        let count = room.completedEntries?[turn.speaker] ?? 0
        return (turn.speaker == me?.id ? "SUA VEZ" : room.name(turn.speaker)) + " · \(count)/4 concluídas"
    }
    init() {
        audio.onPCM = { [weak self] pcm in Task { @MainActor in self?.microphone(pcm) } }
        relay.onAudio = { [weak self] sender,pcm in Task { @MainActor in
            guard let self, self.room?.phase == "live", let battle = self.room?.battle, [battle.a,battle.b].contains(sender) else { return }
            self.audio.receive(sender: sender, pcm: pcm)
        } }
        relay.onError = { [weak self] text in Task { @MainActor in self?.audioMessage = text; self?.audioReady = false } }
        audio.onSpeechEnded = { [weak self] in Task { @MainActor in
            guard let self, let room = self.room, room.mode == "training", let turn = room.turn, turn.id == self.spokenTurn else { return }
            do { let _: Ack = try await self.api!.post("synthetic-done", ["turnId":turn.id], as: Ack.self) } catch { self.message = error.localizedDescription }
        } }
    }
    func join() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do {
            guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw AppError.message("Digite seu nome de MC.") }
            guard !compete || consent else { throw AppError.message("Autorize o uso dos seus turnos para participar como MC.") }
            let connection = try Connection.parse(roomLink)
            if api?.connection.baseURL != connection.baseURL || api?.connection.hostToken != connection.hostToken { api = RoomAPI(connection: connection) }
            guard let api else { return }; health = try await api.health()
            guard health?.nativeAudio?.version == 1 else { throw AppError.message("Este servidor ainda não suporta o app iPhone. Use o servidor atualizado com áudio nativo.") }
            if api.session != nil { do { let _: PollResponse = try await api.post("poll", as: PollResponse.self) } catch { if api.session != nil { throw error } } }
            if api.session == nil {
                let session: Session = try await api.post("join", ["name":name,"role":compete ? "mc":"spectator","invite":connection.invite,"audioTransport":"native-pcm"], as: Session.self)
                api.save(session)
            }
            UserDefaults.standard.set(name,forKey:"mcName")
            // Persist public invitation, never the creator token in UserDefaults.
            UserDefaults.standard.set(invitation,forKey:"roomLink")
            await poll(); startPolling(); message = "Você entrou. Toque em Ativar áudio para preparar o iPhone."
        } catch { message = error.localizedDescription }
    }
    func activateAudio() async {
        guard let api, let session = api.session else { return }
        do {
            let isMC = me?.role == "mc"
            if isMC {
                let granted = await withCheckedContinuation { continuation in AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) } }
                guard granted else { throw AppError.message("Permita o microfone em Ajustes > Free Flows > Microfone.") }
            }
            audio.stop(); try audio.start(record: isMC); relay.connect(connection: api.connection, session: session)
            let _: Ack = try await api.post("ready", ["ready":true], as: Ack.self)
            audioReady = true; backgrounded = false; UIApplication.shared.isIdleTimerDisabled = true
            audioMessage = isMC ? "Microfone preparado · envia voz só no confronto ativo" : "Público · somente reprodução"
            await poll()
        } catch { audio.stop(); audioReady = false; audioMessage = error.localizedDescription }
    }
    func becomeMC() async {
        guard consent, let api else { message = "Autorize o processamento do áudio primeiro."; return }
        do { let _: Ack = try await api.post("compete",["consent":true],as:Ack.self); await poll(); await activateAudio() }
        catch { message = error.localizedDescription }
    }
    func startAvailable() async {
        guard let api, !busy else { return }; busy = true; defer { busy = false }
        do {
            guard nativeOnly else { throw AppError.message("Neste teste use o app iPhone nos dois MCs. O áudio do site ainda não está ligado ao transporte nativo.") }
            if room?.mode != "live" || room?.phase == "complete" {
                guard consent else { throw AppError.message("Autorize o uso dos seus turnos de áudio.") }
                let _: Ack = try await api.post("real-lobby",["consent":true],as:Ack.self)
            }
            let _: Ack = try await api.post("practice",as:Ack.self); await poll()
        } catch { message = error.localizedDescription }
    }
    func solo() async {
        guard let api, consent, !busy else { message = "Autorize o uso dos seus turnos de áudio."; return }
        busy = true; defer { busy = false }
        do {
            let _: Ack = try await api.post("real-lobby",["consent":true],as:Ack.self); await poll(); await activateAudio()
            guard audioReady else { return }
            let _: Ack = try await api.post("solo",["consent":true,"bars":2,"bpm":90],as:Ack.self); await poll()
        } catch { message = error.localizedDescription }
    }
    func poll() async {
        guard let api, let session = api.session else { return }
        let start = Date().timeIntervalSince1970 * 1000
        do {
            let result: PollResponse = try await api.post("poll",as:PollResponse.self)
            guard self.api === api, api.session?.token == session.token else { return }
            let now = Date().timeIntervalSince1970 * 1000
            serverOffset = result.state.serverNow - (start+now)/2; room = result.state
            audio.updateBeat(epoch: result.state.phase == "live" ? result.state.beatEpoch:nil,bpm:result.state.config.bpm,offset:serverOffset)
            if !result.state.isActive(session.id), wasPublishing { if let segment = segments.flush() { queue(segment) }; wasPublishing = false }
            if result.state.mode == "training", let turn = result.state.turn, turn.speaker != me?.id,
               result.state.members.first(where:{$0.id == turn.speaker})?.simulated == true, spokenTurn != turn.id,
               let verse = result.state.transcripts.first(where:{$0.turn == turn.id}) { spokenTurn = turn.id; audio.say(verse.text) }
            if audioReady, let beat = result.state.beat, beat.version != melodyVersion, !loadingMelody {
                loadingMelody = true
                Task { defer { loadingMelody = false }; do { try audio.setMelody(try await api.beat()); melodyVersion = beat.version } catch { audioMessage = "Falha ao carregar beat; usando clique local." } }
            }
        } catch { message = error.localizedDescription; if api.session == nil { stopPolling(); stopAudio() } }
    }
    private func startPolling() {
        stopPolling()
        pollTask = Task { [weak self] in while !Task.isCancelled { guard let self else { break }; await self.poll(); do { try await Task.sleep(for:.milliseconds(500)) } catch { break } } }
    }
    private func stopPolling() { pollTask?.cancel(); pollTask = nil }
    private func microphone(_ pcm: Data) {
        guard audioReady, let room, let me, room.isActive(me.id), let turn = room.turn else { return }
        wasPublishing = true; relay.send(pcm)
        for segment in segments.consume(pcm,turnId:turn.id) { queue(segment) }
    }
    private func queue(_ segment: AudioSegment) {
        guard uploadBytes + segment.data.count <= 20*1024*1024 else { transcriptionMessage = "Fila cheia; este trecho não foi guardado. O áudio ao vivo continua."; return }
        uploads.append(segment); uploadBytes += segment.data.count
        guard uploadTask == nil else { return }
        uploadTask = Task { [weak self] in guard let self else { return }; await self.drain() }
    }
    private func drain() async {
        guard let owner = api else { uploadTask = nil; return }
        defer { if api === owner { uploadTask = nil } }
        while !uploads.isEmpty, !Task.isCancelled {
            guard let api, api === owner else { return }; let segment = uploads[0]; var delivered = false
            for attempt in 0..<3 {
                guard !Task.isCancelled else { return }
                do { try await api.send(segment); delivered = true; break }
                catch { transcriptionMessage = "IA indisponível; trecho guardado nesta sessão. Tentando novamente…"; if attempt < 2 { try? await Task.sleep(for:.seconds(2*(attempt+1))) } }
            }
            guard delivered else { transcriptionMessage = "Trechos pendentes nesta sessão. A contagem pode atrasar; a voz continua."; return }
            guard !Task.isCancelled, self.api === owner, uploads.first?.turnId == segment.turnId, uploads.first?.chunkIndex == segment.chunkIndex else { return }
            uploadBytes -= uploads.removeFirst().data.count
            transcriptionMessage = uploads.isEmpty ? "Transcrição em dia":"Processando \(uploads.count) trecho(s)…"
        }
    }
    func stopAudio() { audio.stop(); relay.disconnect(); audioReady = false; level = 0; UIApplication.shared.isIdleTimerDisabled = false }
    func suspend() {
        backgrounded = true
        if let segment = segments.flush() { queue(segment) }
        stopAudio(); stopPolling(); audioMessage = "Áudio pausado. Ao voltar, toque em Retomar áudio."
        api?.markNotReady()
    }
    func resume() { if backgrounded { startPolling(); message = "Toque em Retomar áudio para voltar à batalha." } }
    func leave() {
        suspend(); uploadTask?.cancel(); uploadTask = nil; uploads.removeAll(); uploadBytes = 0
        api?.clearSession(); api = nil; room = nil; segments = SpeechSegments(); spokenTurn = nil; melodyVersion = nil
        message = "Você saiu. Os trechos pendentes desta sessão foram descartados."
    }
}
