import Foundation

struct Participant: Decodable, Identifiable {
    let id: String
    let name: String
    let role: String
    let connected: Bool
    let eliminated: Bool
    let audioReady: Bool?
    let simulated: Bool?
    let audioTransport: String?
}
struct Battle: Decodable, Identifiable {
    let id: String
    let round: Int
    let a: String?
    let b: String?
    let status: String
    let winner: String?
    let assessment: String?
}
struct Turn: Decodable { let id: String; let speaker: String; let index: Int; let start: Double; let end: Double? }
struct RoomConfig: Decodable { let bars: Int; let bpm: Double; let freeFlow: Bool? }
struct Management: Decodable { let status: String; let message: String }
struct Verse: Decodable, Identifiable {
    let match: String; let turn: String; let speaker: String; let text: String; let at: Double; let source: String
    var id: String { "\(speaker)-\(turn)-\(at)-\(text.prefix(16))" }
}
struct BeatInfo: Decodable { let name: String; let version: Double }
struct RoomState: Decodable {
    let members: [Participant]; let matches: [Battle]; let phase: String; let turn: Turn?
    let mode: String; let active: String?; let config: RoomConfig; let management: Management
    let transcripts: [Verse]; let serverNow: Double; let beatEpoch: Double?; let completedEntries: [String:Int]?
    let beat: BeatInfo?
    var battle: Battle? { matches.first { $0.id == active } ?? matches.first { $0.status == "pending" } ?? matches.last }
    func name(_ id: String?) -> String { members.first { $0.id == id }?.name ?? "A definir" }
    func isActive(_ id: String) -> Bool { phase == "live" && [battle?.a, battle?.b].contains(id) }
}
struct Session: Codable { let id: String; let token: String }
struct PollResponse: Decodable { let state: RoomState }
struct Ack: Decodable { let ok: Bool }
struct Health: Decodable {
    struct AI: Decodable { let primary: Bool; let secondary: Bool; let active: String }
    struct NativeCapability: Decodable { let version: Int; let sampleRate: Int }
    let ok: Bool; let gemini: Bool; let ai: AI?; let nativeAudio: NativeCapability?
}
struct AudioSegment: Encodable {
    let turnId: String; let chunkIndex: Int; let speechEnded: Bool
    let mimeType = "audio/wav"
    let data: String
}
enum AppError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}
