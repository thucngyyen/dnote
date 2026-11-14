import SwiftUI

@main
struct DNoteApp: App {
    @State private var model = NotesModel()
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("theme") private var theme: AppTheme = .system

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(.primary)
                .preferredColorScheme(theme.colorScheme)
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: model.appBecameActive()
            case .background, .inactive: model.flushSave()
            @unknown default: break
            }
        }
    }
}
