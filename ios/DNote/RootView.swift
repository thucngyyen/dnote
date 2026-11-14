import SwiftUI

struct RootView: View {
    @Environment(NotesModel.self) private var model
    @Namespace private var zoom

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $model.route) {
            HomeView()
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .folder(let path): FolderView(folder: path)
                    case .note(let path): NoteView(path: path).zoomDestination(model.zoomID(for: path))
                    case .image(let path): ImageViewer(path: path).zoomDestination(model.zoomID(for: path))
                    }
                }
        }
        .environment(\.zoomNamespace, zoom)
        .actionSheets()
        .tint(.dnAccent)
        .alert("DNote", isPresented: Binding(get: { model.alert != nil }, set: { if !$0 { model.alert = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.alert ?? "")
        }
    }
}

struct SyncBadge: View {
    @Environment(NotesModel.self) private var model

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(label).font(.footnote).foregroundStyle(Color.dnMuted).monospacedDigit()
        }
        .accessibilityElement(children: .combine)
    }

    private var label: String {
        switch model.status {
        case .localOnly: "On this device"
        case .syncing: "Syncing…"
        case .idle(let d): "Synced \(d.formatted(date: .omitted, time: .shortened))"
        case .failed: "Sync failed"
        }
    }

    private var color: Color {
        switch model.status {
        case .localOnly: .dnFaint
        case .syncing: .orange
        case .idle: .dnSuccess
        case .failed: .red
        }
    }
}
