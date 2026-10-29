import SwiftUI

@main @MainActor struct FreeFlowsApp: App {
    @StateObject private var model = RoomModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            ArenaView().environmentObject(model)
                .onChange(of:scenePhase) { _,phase in
                    if phase == .background { model.suspend() }
                    else if phase == .active { model.resume() }
                }
        }
    }
}
