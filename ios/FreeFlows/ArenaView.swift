import SwiftUI

struct ArenaView: View {
    @EnvironmentObject private var model: RoomModel
    private let orange = Color(red:1,green:0.46,blue:0.28)
    private let panel = Color(red:0.085,green:0.09,blue:0.12)
    var body: some View {
        ScrollView {
            VStack(alignment:.leading,spacing:20) {
                HStack {
                    Text("FREE").foregroundColor(.white) + Text("FLOWS").italic().foregroundColor(orange)
                    Spacer()
                    Text("iPHONE · TESTE").font(.caption2).foregroundStyle(.secondary)
                }.font(.title2.bold())
                if !model.joined { entry }
                else {
                    arena
                    if let me = model.me {
                        HStack { VStack(alignment:.leading) { Text(me.name).font(.headline); Text(me.role == "mc" ? "MC · microfones livres no confronto":"Público · somente reprodução").font(.caption).foregroundStyle(.secondary) }; Spacer(); ShareLink(item:model.invitation) { Image(systemName:"square.and.arrow.up") }.tint(orange) }.padding().background(panel,in:RoundedRectangle(cornerRadius:16))
                    }
                    Text(model.message).font(.callout).foregroundStyle(.secondary)
                    if model.isCreator, model.room?.phase != "live", model.room?.phase != "judging" {
                        VStack(alignment:.leading,spacing:14) {
                            Text("Sua arena, sua voz.").font(.title3.bold())
                            Text("\(model.available) MCs prontos. O teste começa com 2.").font(.callout).foregroundStyle(.secondary)
                            Button("Batalhar com disponíveis") { Task { await model.startAvailable() } }.buttonStyle(PrimaryButton()).disabled(model.available<2 || model.busy)
                            Button("Treinar sozinho no bate-volta") { Task { await model.solo() } }.buttonStyle(.bordered).tint(orange).disabled(model.busy)
                            Toggle("Autorizo meus turnos de áudio no Gemini para transcrição e análise",isOn:$model.consent).font(.caption).tint(orange)
                            Text("No treino, o oponente tem versos roteirizados e voz sintética. Não é uma IA improvisando.").font(.caption2).foregroundStyle(.secondary)
                        }.padding().background(panel,in:RoundedRectangle(cornerRadius:16))
                    }
                    if model.me?.role == "spectator",model.room?.mode == "live",model.room?.phase == "lobby" {
                        Toggle("Autorizo meus turnos no Gemini",isOn:$model.consent).font(.caption).tint(orange)
                        Button("Quero competir") { Task { await model.becomeMC() } }.buttonStyle(.bordered).tint(orange)
                    }
                    roster
                    if let room = model.room, !room.matches.isEmpty { bracket(room) }
                    VStack(alignment:.leading,spacing:14) {
                        Text("VERSO A VERSO").font(.caption.bold()).tracking(2)
                        Text(model.transcriptionMessage).font(.caption).foregroundStyle(.secondary)
                        ForEach(Array((model.room?.transcripts ?? []).suffix(8))) { verse in
                            VStack(alignment:.leading,spacing:6) {
                                Text(model.room?.name(verse.speaker) ?? "MC").font(.caption.bold()).foregroundStyle(orange)
                                Text(verse.text.isEmpty ? "[inaudível]":verse.text).font(.callout)
                                Text(verse.source == "demo-script" ? "ROTEIRO DE TREINO":"TRANSCRIÇÃO GEMINI").font(.system(size:9)).foregroundStyle(.secondary)
                            }; Divider()
                        }
                    }.padding().background(panel,in:RoundedRectangle(cornerRadius:16))
                    if let assessment = model.room?.matches.last(where:{$0.assessment != nil})?.assessment {
                        VStack(alignment:.leading,spacing:10) { Text("LEITURA DA BATALHA").font(.caption.bold()); Text(assessment).font(.callout) }.padding().background(panel,in:RoundedRectangle(cornerRadius:16))
                    }
                    Button("Sair da sala") { model.leave() }.font(.caption).foregroundStyle(.secondary)
                }
                Text("POC nativa · áudio e reconhecimento das entradas ainda em validação. Use fones e mantenha o app aberto durante a batalha.").font(.caption2).foregroundStyle(.secondary)
            }.padding(20)
        }
        .background(Color(red:0.04,green:0.045,blue:0.06))
        .safeAreaInset(edge:.bottom) {
            if model.joined {
                VStack(spacing:8) {
                    Text(model.audioMessage).font(.caption).multilineTextAlignment(.center).foregroundStyle(.secondary)
                    Button(model.audioReady ? "Áudio ativo · tocar para reconectar":"Ativar / retomar áudio") { Task { await model.activateAudio() } }.buttonStyle(PrimaryButton())
                }.padding().background(.ultraThinMaterial)
            }
        }
        .preferredColorScheme(.dark)
    }
    private var entry: some View {
        VStack(alignment:.leading,spacing:18) {
            Text("O próximo verso\né seu.").font(.system(size:46,weight:.heavy)).tracking(-2)
            Text("Entre pelo app. O microfone é nativo do iPhone.").foregroundStyle(.secondary)
            TextField("Cole o convite HTTPS da sala",text:$model.roomLink,axis:.vertical).textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(.roundedBorder).lineLimit(2...4)
            TextField("Seu nome de MC",text:$model.name).textInputAutocapitalization(.words).textFieldStyle(.roundedBorder)
            Toggle("Quero competir como MC",isOn:$model.compete).tint(orange)
            if model.compete { Toggle("Autorizo o envio dos meus turnos ao Gemini para transcrição e julgamento",isOn:$model.consent).font(.caption).tint(orange) }
            Button(model.busy ? "Entrando…":"Entrar na arena") { Task { await model.join() } }.buttonStyle(PrimaryButton()).disabled(model.busy)
            Text(model.message).font(.callout).foregroundStyle(orange)
            Text("Cole o link de criador somente no seu iPhone se for preparar a sala. Compartilhe o convite comum com os outros MCs.").font(.caption).foregroundStyle(.secondary)
        }
    }
    private var arena: some View {
        VStack(spacing:22) {
            HStack { Label("SALA PRINCIPAL",systemImage:"circle.fill").font(.caption2.bold()).foregroundStyle(.green); Spacer(); Text(model.room?.mode == "training" ? "TREINO SOLO":"AO VIVO").font(.caption2).foregroundStyle(.secondary) }
            let battle = model.room?.battle
            HStack(alignment:.center) {
                competitor(battle?.a)
                Text("VS").font(.title3.italic()).foregroundStyle(.secondary)
                competitor(battle?.b)
            }
            Text(model.turnLabel).font(.headline).foregroundStyle(orange).multilineTextAlignment(.center).accessibilityAddTraits(.updatesFrequently)
            Text("4 entradas por MC · sem cronômetro\nA indicação não corta os microfones.").font(.caption).multilineTextAlignment(.center).foregroundStyle(.secondary)
            Text(model.room?.management.message ?? "Aguardando participantes").font(.caption).foregroundStyle(.secondary)
        }.padding(20).background(panel,in:RoundedRectangle(cornerRadius:20))
    }
    private func competitor(_ id: String?) -> some View {
        let name = model.room?.name(id) ?? "A definir"
        return VStack(spacing:12) {
            Text(String(name.prefix(2)).uppercased()).font(.title.bold()).frame(width:70,height:70).background(orange.opacity(0.15),in:RoundedRectangle(cornerRadius:20)).overlay(RoundedRectangle(cornerRadius:20).stroke(orange.opacity(0.6)))
            Text(name).font(.headline).multilineTextAlignment(.center).lineLimit(2)
            Text("\(model.room?.completedEntries?[id ?? ""] ?? 0)/4 concluídas").font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth:.infinity)
    }
    private var roster: some View {
        VStack(alignment:.leading,spacing:14) {
            Text("NA RODA").font(.caption.bold()).tracking(2)
            ForEach(model.room?.members ?? []) { member in
                HStack { VStack(alignment:.leading,spacing:4) { Text(member.name).font(.headline); Text(member.simulated == true ? "MC fictício":member.eliminated ? "Eliminado":member.role == "spectator" ? "Público":member.audioReady == true && member.connected ? "MC pronto":"Aguardando áudio / conexão").font(.caption).foregroundStyle(.secondary) }; Spacer(); Circle().fill(member.connected ? Color.green:Color.gray).frame(width:7,height:7) }; Divider()
            }
        }.padding().background(panel,in:RoundedRectangle(cornerRadius:16))
    }
    private func bracket(_ room: RoomState) -> some View {
        VStack(alignment:.leading,spacing:12) {
            Text("CHAVEAMENTO").font(.caption.bold()).tracking(2)
            ForEach(room.matches) { match in VStack(alignment:.leading,spacing:5) { Text("R\(match.round) · \(room.name(match.a)) × \(room.name(match.b))").font(.callout); Text(match.winner == nil ? match.status:"Vencedor: \(room.name(match.winner))").font(.caption).foregroundStyle(.secondary) }; Divider() }
        }.padding().background(panel,in:RoundedRectangle(cornerRadius:16))
    }
}
struct PrimaryButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.headline).frame(maxWidth:.infinity).padding(.vertical,15)
            .foregroundStyle(Color.black).background(Color(red:1,green:0.46,blue:0.28).opacity(configuration.isPressed ? 0.7:1),in:RoundedRectangle(cornerRadius:12))
    }
}
