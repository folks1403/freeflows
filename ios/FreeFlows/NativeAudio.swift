import Foundation
import AVFoundation

// Native audio only: no WebView, JavaScript microphone or browser media APIs.
final class NativeAudio: NSObject, AVSpeechSynthesizerDelegate {
    private let engine = AVAudioEngine()
    private let beatNode = AVAudioPlayerNode()
    private var speakers: [String:AVAudioPlayerNode] = [:]
    private let outputFormat = AVAudioFormat(standardFormatWithSampleRate: 16000, channels: 1)!
    private var converter: AVAudioConverter?
    private var installedTap = false
    private var timer: DispatchSourceTimer?
    private var beatEpoch: Double?
    private var bpm = 90.0
    private var offset = 0.0
    private var lastBeat = -1
    private var melody: AVAudioPlayer?
    private var melodyEpoch: Double?
    private let speech = AVSpeechSynthesizer()
    var onPCM: ((Data) -> Void)?
    var onSpeechEnded: (() -> Void)?
    private(set) var running = false

    override init() { super.init(); speech.delegate = self }
    func start(record: Bool) throws {
        guard !running else { return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker,.allowBluetooth])
        try session.setPreferredIOBufferDuration(0.02)
        try session.setActive(true)
        if !engine.attachedNodes.contains(beatNode) {
            engine.attach(beatNode); engine.connect(beatNode, to: engine.mainMixerNode, format: outputFormat)
        }
        if record {
            let input = engine.inputNode
            try input.setVoiceProcessingEnabled(true)
            let inputFormat = input.outputFormat(forBus: 0)
            guard inputFormat.sampleRate > 0,
                  let pcm = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true),
                  let converter = AVAudioConverter(from: inputFormat, to: pcm) else {
                throw AppError.message("Formato de microfone indisponível. Confira a rota de áudio.")
            }
            self.converter = converter
            input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
                let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * 16000 / buffer.format.sampleRate) + 32)
                guard let output = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: capacity) else { return }
                var supplied = false
                var error: NSError?
                converter.convert(to: output, error: &error) { _, status in
                    if supplied { status.pointee = .noDataNow; return nil }
                    supplied = true; status.pointee = .haveData; return buffer
                }
                guard error == nil, output.frameLength > 0, let samples = output.int16ChannelData?[0] else { return }
                self?.onPCM?(Data(bytes: samples, count: Int(output.frameLength) * 2))
            }
            installedTap = true
        }
        engine.prepare(); try engine.start(); beatNode.play(); running = true
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now(), repeating: .milliseconds(20))
        timer.setEventHandler { [weak self] in self?.tickBeat() }
        timer.resume(); self.timer = timer
    }
    func stop() {
        timer?.cancel(); timer = nil
        speech.stopSpeaking(at: .immediate)
        melody?.stop(); melodyEpoch = nil
        if installedTap { engine.inputNode.removeTap(onBus: 0); installedTap = false }
        speakers.values.forEach { $0.stop() }; beatNode.stop(); engine.stop()
        running = false; converter = nil; beatEpoch = nil; lastBeat = -1
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    func updateBeat(epoch: Double?, bpm: Double, offset: Double) {
        if self.beatEpoch != epoch { lastBeat = -1; melodyEpoch = nil }
        self.beatEpoch = epoch; self.bpm = bpm; self.offset = offset
        if epoch == nil { melody?.stop(); speech.stopSpeaking(at: .immediate) }
    }
    func setMelody(_ data: Data) throws {
        melody?.stop(); melody = try AVAudioPlayer(data: data); melody?.numberOfLoops = -1
        melody?.volume = 0.25; melody?.prepareToPlay(); melodyEpoch = nil
    }
    private func tickBeat() {
        guard running, let epoch = beatEpoch else { return }
        let now = Date().timeIntervalSince1970 * 1000 + offset
        guard now >= epoch else { return }
        if let melody {
            if melodyEpoch != epoch {
                melody.currentTime = ((now - epoch) / 1000).truncatingRemainder(dividingBy: max(0.01,melody.duration))
                melody.play(); melodyEpoch = epoch
            }; return
        }
        let index = Int((now - epoch) / (60000 / bpm))
        guard index != lastBeat else { return }; lastBeat = index
        let length: AVAudioFrameCount = 1600
        guard let buffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: length), let floats = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = length
        for i in 0..<Int(length) {
            let t = Double(i) / 16000
            floats[i] = Float(sin(2 * Double.pi * (index % 4 == 0 ? 95 : 180) * t) * exp(-t * 65) * 0.14)
        }
        beatNode.scheduleBuffer(buffer)
    }
    func receive(sender: String, pcm: Data) {
        guard running, pcm.count > 0, pcm.count % 2 == 0 else { return }
        let player: AVAudioPlayerNode
        if let existing = speakers[sender] { player = existing }
        else {
            player = AVAudioPlayerNode(); speakers[sender] = player
            engine.attach(player); engine.connect(player, to: engine.mainMixerNode, format: outputFormat); player.play()
        }
        let count = pcm.count / 2
        guard let buffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: AVAudioFrameCount(count)), let floats = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = AVAudioFrameCount(count)
        pcm.withUnsafeBytes { bytes in
            for i in 0..<count { floats[i] = Float(Int16(littleEndian: bytes.loadUnaligned(fromByteOffset: i * 2, as: Int16.self))) / 32768 }
        }
        player.scheduleBuffer(buffer)
    }
    func say(_ text: String) {
        speech.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text); utterance.voice = AVSpeechSynthesisVoice(language: "pt-BR")
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 1.1; speech.speak(utterance)
    }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) { onSpeechEnded?() }
}

@MainActor final class NativeRelay {
    private var task: URLSessionWebSocketTask?
    private var sending = 0
    private var generation = UUID()
    var onAudio: ((String,Data) -> Void)?
    var onError: ((String) -> Void)?
    func connect(connection: Connection, session: Session) {
        disconnect()
        var url = URLComponents(url: connection.baseURL, resolvingAgainstBaseURL: false)!
        url.scheme = "wss"; url.path = "/native-audio"; url.query = nil; url.fragment = nil
        var request = URLRequest(url: url.url!); request.setValue("Bearer " + session.token, forHTTPHeaderField: "Authorization")
        let task = URLSession.shared.webSocketTask(with: request); self.task = task
        let generation = self.generation; task.resume(); receive(task, generation: generation)
    }
    private func receive(_ task: URLSessionWebSocketTask, generation: UUID) {
        task.receive { [weak self] result in
            Task { @MainActor in
            guard let self, self.generation == generation else { return }
            switch result {
            case .success(.data(let data)):
                if data.count > 36, let id = String(data: data.prefix(36), encoding: .ascii) { self.onAudio?(id,Data(data.dropFirst(36))) }
                self.receive(task, generation: generation)
            case .success(.string(let text)):
                if let data = text.data(using: .utf8), let object = (try? JSONSerialization.jsonObject(with: data)) as? [String:Any], let message = object["error"] as? String { self.onError?(message) }
                self.receive(task, generation: generation)
            case .failure: self.onError?("Conexão de áudio perdida. Toque em Retomar áudio.")
            @unknown default: break
            }
            }
        }
    }
    func send(_ data: Data) {
        guard sending < 6, let task else { return }
        sending += 1
        let generation = self.generation
        task.send(.data(data)) { [weak self] error in
            Task { @MainActor in
                guard let self, self.generation == generation else { return }; self.sending = max(0,self.sending - 1)
                if error != nil { self.onError?("Falha no envio de voz. Confira a conexão.") }
            }
        }
    }
    func disconnect() { generation = UUID(); task?.cancel(with: .goingAway, reason: nil); task = nil; sending = 0 }
}

// Segmentation limits request size, never the live microphone or battle duration.
struct SpeechSegments {
    private var pcm = Data()
    private var speechFrames = 0
    private var silenceFrames = 0
    private var index = 0
    private var turn: String?
    mutating func consume(_ data: Data, turnId: String) -> [AudioSegment] {
        var completed: [AudioSegment] = []
        if turn != turnId { if let item = flush(ended: false) { completed.append(item) }; turn = turnId; index = 0 }
        pcm.append(data)
        let count = data.count / 2
        let energy: Double = data.withUnsafeBytes { raw in
            var sum = 0.0
            for i in 0..<count { let v = Double(Int16(littleEndian: raw.loadUnaligned(fromByteOffset: i * 2, as: Int16.self))) / 32768; sum += v*v }
            return sqrt(sum / Double(max(1,count)))
        }
        if energy > 0.018 { speechFrames += count; silenceFrames = 0 } else { silenceFrames += count }
        let ended = speechFrames >= 4800 && silenceFrames >= 22400
        if ended || pcm.count >= 640000 { if let item = flush(ended: ended) { completed.append(item) } }
        return completed
    }
    mutating func flush(ended: Bool = false) -> AudioSegment? {
        defer { pcm.removeAll(keepingCapacity: true); speechFrames = 0; silenceFrames = 0 }
        guard let turn, speechFrames >= 4800 else { return nil }
        let item = AudioSegment(turnId: turn, chunkIndex: index, speechEnded: ended, data: Self.wav(pcm).base64EncodedString()); index += 1
        return item
    }
    static func wav(_ pcm: Data) -> Data {
        var data = Data()
        func string(_ s: String) { data.append(contentsOf: s.utf8) }
        func u16(_ n: UInt16) { var v = n.littleEndian; withUnsafeBytes(of: &v) { data.append(contentsOf: $0) } }
        func u32(_ n: UInt32) { var v = n.littleEndian; withUnsafeBytes(of: &v) { data.append(contentsOf: $0) } }
        string("RIFF"); u32(UInt32(36 + pcm.count)); string("WAVEfmt "); u32(16); u16(1); u16(1)
        u32(16000); u32(32000); u16(2); u16(16); string("data"); u32(UInt32(pcm.count)); data.append(pcm); return data
    }
}
