import Foundation
import Security

// No Gemini key belongs in the app. Only room/session tokens are stored in Keychain.
enum SecretStore {
    static func read(_ account: String) -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "FreeFlows.Test", kSecAttrAccount as String: account,
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    static func write(_ value: String?, account: String) {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "FreeFlows.Test", kSecAttrAccount as String: account]
        SecItemDelete(query as CFDictionary)
        guard let value else { return }
        var item = query
        item[kSecValueData as String] = Data(value.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }
}
struct Connection {
    let baseURL: URL; let invite: String; let hostToken: String
    static func parse(_ text: String) throws -> Connection {
        guard let c = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              c.scheme == "https", let host = c.host, !host.isEmpty,
              c.user == nil, c.password == nil else {
            throw AppError.message("Cole o convite HTTPS completo da sua sala.")
        }
        let invite = c.queryItems?.first { $0.name == "invite" }?.value ?? ""
        guard !invite.isEmpty else { throw AppError.message("O link precisa conter o convite da sala.") }
        var root = c; root.path = ""; root.query = nil; root.fragment = nil
        guard let base = root.url else { throw AppError.message("Endereço inválido.") }
        let control = URLComponents(string: "https://local/?" + (c.fragment ?? ""))?.queryItems?.first { $0.name == "control" }?.value
        return Connection(baseURL: base, invite: invite,
                          hostToken: control ?? SecretStore.read(host + ".control") ?? "")
    }
}
final class RoomAPI {
    let connection: Connection
    var session: Session?
    private let http: URLSession
    init(connection: Connection) {
        self.connection = connection
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 40; config.timeoutIntervalForResource = 50
        http = URLSession(configuration: config)
        if let saved = SecretStore.read(connection.baseURL.host! + ".session"), let data = saved.data(using: .utf8) {
            session = try? JSONDecoder().decode(Session.self, from: data)
        }
        if !connection.hostToken.isEmpty { SecretStore.write(connection.hostToken, account: connection.baseURL.host! + ".control") }
    }
    func clearSession() { session = nil; SecretStore.write(nil, account: connection.baseURL.host! + ".session") }
    func markNotReady() {
        guard let session else { return }
        var request = URLRequest(url: connection.baseURL.appendingPathComponent("ready"))
        request.httpMethod = "POST"; request.setValue("application/json",forHTTPHeaderField:"Content-Type")
        request.setValue("Bearer " + session.token,forHTTPHeaderField:"Authorization")
        request.httpBody = Data("{\"ready\":false}".utf8)
        Task { _ = try? await http.data(for:request) }
    }
    func save(_ session: Session) {
        self.session = session
        if let data = try? JSONEncoder().encode(session) { SecretStore.write(String(data: data, encoding: .utf8), account: connection.baseURL.host! + ".session") }
    }
    func post<T: Decodable>(_ path: String, _ body: [String: Any] = [:], as type: T.Type) async throws -> T {
        var request = URLRequest(url: connection.baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let session { request.setValue("Bearer " + session.token, forHTTPHeaderField: "Authorization") }
        if !connection.hostToken.isEmpty { request.setValue(connection.hostToken, forHTTPHeaderField: "X-Host-Token") }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await http.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw AppError.message("Servidor sem resposta.") }
        if path == "poll" && [401,403,404].contains(response.statusCode) { clearSession() }
        guard (200..<300).contains(response.statusCode) else {
            let error = (try? JSONSerialization.jsonObject(with: data)) as? [String:Any]
            throw AppError.message(error?["error"] as? String ?? "Erro HTTP \(response.statusCode)")
        }
        return try JSONDecoder().decode(T.self, from: data)
    }
    func health() async throws -> Health {
        let (data, response) = try await http.data(from: connection.baseURL.appendingPathComponent("health"))
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw AppError.message("Sala fora do ar. Confira o link atual.") }
        return try JSONDecoder().decode(Health.self, from: data)
    }
    func beat() async throws -> Data {
        var url = URLComponents(url: connection.baseURL.appendingPathComponent("beat"), resolvingAgainstBaseURL: false)!
        url.queryItems = [URLQueryItem(name: "invite", value: connection.invite)]
        let (data, response) = try await http.data(from: url.url!)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw AppError.message("Não foi possível carregar o beat.") }
        return data
    }
    func send(_ segment: AudioSegment) async throws {
        let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(segment)) as! [String:Any]
        let _: Ack = try await post("audio", object, as: Ack.self)
    }
}
