import SwiftUI

/// Home: header, search, pinned cards, scope chips (sliding pill) and the list for the
/// selected scope, which slides in from the side of the newly selected chip.
struct HomeView: View {
    @Environment(NotesModel.self) private var model
    @State private var query = ""
    @State private var scope: Scope = .all
    @State private var forward = true
    @State private var showSettings = false
    @FocusState private var searching: Bool

    var body: some View {
        VStack(spacing: 0) {
            header
            searchField
            if query.isEmpty {
                if !pinned.isEmpty { pinnedSection }
                ScopeChips(chips: chips, selection: $scope, onSelect: select)
                    .padding(.top, 10)
                    .padding(.bottom, 6)
                if case .folder(let path) = scope, let folder = model.entry(path) {
                    FolderHeader(folder: folder)
                        .transition(.opacity)
                }
                ZStack {
                    ScopeList(scope: scope)
                        .id(scope)
                        .transition(.asymmetric(
                            insertion: .move(edge: forward ? .trailing : .leading).combined(with: .opacity),
                            removal: .move(edge: forward ? .leading : .trailing).combined(with: .opacity)
                        ))
                }
                .frame(maxHeight: .infinity, alignment: .top)
                .clipped()
            } else {
                SearchResults(query: query)
            }
        }
        .background(Color.dnChrome.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .bottom) {
            if !searching {
                Button { model.newNote(in: newNoteFolder) } label: {
                    Label("New note", systemImage: "square.and.pencil")
                }
                .buttonStyle(PrimaryButtonStyle())
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 4)
                .background(LinearGradient(colors: [Color.dnChrome.opacity(0), Color.dnChrome], startPoint: .top, endPoint: .center))
            }
        }
        .sheet(isPresented: $showSettings) { SettingsView() }
        // Back on the home list: the next open zooms from a row unless a pinned card is tapped.
        .onChange(of: model.route.isEmpty) { _, atHome in if atHome { model.zoomFromPinned = nil } }
        .onChange(of: model.entries) { _, _ in
            if !chips.contains(where: { $0.scope == scope }) { select(.all) }
        }
        .onChange(of: model.tags) { _, _ in
            if !chips.contains(where: { $0.scope == scope }) { select(.all) }
        }
    }

    // MARK: Sections

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Wordmark(size: 24)
                Spacer()
                Menu {
                    Button { model.newNote(in: newNoteFolder) } label: { Label("New Note", systemImage: "square.and.pencil") }
                    Button { model.prompt = .newFolder(parent: newNoteFolder) } label: {
                        Label(newNoteFolder.isEmpty ? "New Folder" : "New Folder in \((newNoteFolder as NSString).lastPathComponent)", systemImage: "folder.badge.plus")
                    }
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Color.dnText2)
                        .frame(width: 38, height: 38)
                        .background(Color.dnSurface, in: Circle())
                        .overlay(Circle().stroke(Color.dnBorder, lineWidth: 1))
                }
                .accessibilityLabel("Add")
                Button { showSettings = true } label: {
                    Image(systemName: "gearshape")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(Color.dnText2)
                        .frame(width: 38, height: 38)
                        .background(Color.dnSurface, in: Circle())
                        .overlay(Circle().stroke(Color.dnBorder, lineWidth: 1))
                }
                .accessibilityLabel("Settings")
            }
            Button { model.sync() } label: { SyncBadge() }
                .buttonStyle(.plain)
                .disabled(!model.isConnected)
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .padding(.bottom, 10)
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(Color.dnFaint)
            TextField("Search notes", text: $query)
                .focused($searching)
                .submitLabel(.search)
                .autocorrectionDisabled()
            if !query.isEmpty {
                Button { query = ""; searching = false } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Color.dnFaint)
                }
                .accessibilityLabel("Clear search")
            }
        }
        .font(.system(size: 16))
        .padding(.horizontal, 12)
        .frame(height: 42)
        .background(Color.dnSurface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(searching ? Color.dnAccent : Color.dnBorder, lineWidth: 1))
        .padding(.horizontal, 16)
    }

    private var pinned: [Entry] {
        model.pins.compactMap { model.entry($0) }
    }

    private var pinnedSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel(title: "Pinned", systemImage: "pin.fill")
                .padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(pinned, id: \.path) { PinnedCard(entry: $0) }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 2)
            }
        }
        .padding(.top, 14)
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    // MARK: Scope

    private var chips: [ScopeChip] {
        var out = [ScopeChip(scope: .all, label: "All", icon: "tray.full")]
        out += model.topFolders().map { ScopeChip(scope: .folder($0.path), label: $0.name, icon: model.isPinned($0.path) ? "pin.fill" : "folder", entry: $0) }
        out += model.tags.map { ScopeChip(scope: .tag($0.id), label: $0.name, tag: $0) }
        return out
    }

    private func select(_ next: Scope) {
        guard next != scope else { return }
        let ids = chips.map(\.scope)
        forward = (ids.firstIndex(of: next) ?? 0) >= (ids.firstIndex(of: scope) ?? 0)
        withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) { scope = next }
    }

    private var newNoteFolder: String {
        if case .folder(let p) = scope { return p }
        return ""
    }
}

struct SectionLabel: View {
    let title: String
    var systemImage: String?

    var body: some View {
        HStack(spacing: 5) {
            if let systemImage { Image(systemName: systemImage).font(.system(size: 10, weight: .bold)).rotationEffect(.degrees(systemImage.hasPrefix("pin") ? 45 : 0)) }
            Text(title.uppercased()).font(.system(size: 11, weight: .semibold)).tracking(0.6)
        }
        .foregroundStyle(Color.dnMuted)
    }
}

/// The list for one scope. Each scope is its own List so the whole list can slide.
/// Shown above the list when a folder chip is selected: what it is, and everything you can do with it.
struct FolderHeader: View {
    let folder: Entry
    @Environment(NotesModel.self) private var model

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "folder.fill").font(.system(size: 15)).foregroundStyle(Color.dnAccent)
            VStack(alignment: .leading, spacing: 1) {
                Text(folder.name).font(.system(size: 15, weight: .semibold)).foregroundStyle(Color.dnText)
                Text("\(model.noteCount(in: folder.path)) notes").font(.system(size: 12)).foregroundStyle(Color.dnMuted)
            }
            Spacer()
            Menu {
                EntryMenu(entry: folder)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Color.dnText2)
                    .frame(width: 34, height: 34)
                    .background(Color.dnSurface, in: Circle())
                    .overlay(Circle().stroke(Color.dnBorder, lineWidth: 1))
            }
            .accessibilityLabel("Folder actions")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }
}

struct ScopeList: View {
    let scope: Scope
    @Environment(NotesModel.self) private var model

    var body: some View {
        let items = model.items(for: scope)
        List {
            if scope == .all, !model.conflicts.isEmpty { ConflictBanner() }
            if items.isEmpty {
                EmptyScope(scope: scope)
            }
            ForEach(items, id: \.path) { entry in
                EntryRow(entry: entry, showFolder: scope == .all || isTag)
                    .listRowBackground(Color.dnChrome)
                    .listRowSeparatorTint(Color.dnBorder)
                    .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
            }
            Color.clear.frame(height: 70).listRowBackground(Color.clear).listRowSeparator(.hidden)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .refreshable { await model.syncAndWait() }
    }

    private var isTag: Bool {
        if case .tag = scope { return true }
        return false
    }
}

struct EmptyScope: View {
    let scope: Scope
    @Environment(NotesModel.self) private var model

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon).font(.system(size: 30, weight: .light)).foregroundStyle(Color.dnFaint)
            Text(title).font(.headline).foregroundStyle(Color.dnText)
            Text(text).font(.subheadline).foregroundStyle(Color.dnMuted).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }

    private var icon: String {
        if case .tag = scope { return "tag" }
        return "doc.text"
    }

    private var title: String {
        switch scope {
        case .all: "No notes yet"
        case .folder: "This folder is empty"
        case .tag(let id): "Nothing tagged \(model.tags.first { $0.id == id }?.name ?? "")"
        }
    }

    private var text: String {
        if case .tag = scope { return "Long-press a note or folder and choose Tags…" }
        return "Notes are Markdown files stored on this device."
    }
}

struct ConflictBanner: View {
    @Environment(NotesModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 7) {
                Circle().fill(.orange).frame(width: 6, height: 6)
                Text("Edits clashed during sync").font(.subheadline.weight(.semibold))
            }
            Text("Both versions were kept in: \(model.conflicts.map(\.noteTitle).joined(separator: ", ")).")
                .font(.footnote).foregroundStyle(Color.dnMuted)
            Button("Dismiss") { model.conflicts = [] }.font(.footnote.weight(.medium))
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.dnSurface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Color.dnBorder))
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 8, trailing: 16))
    }
}

struct SearchResults: View {
    let query: String
    @Environment(NotesModel.self) private var model

    var body: some View {
        let hits = model.search(query)
        List {
            if hits.isEmpty {
                ContentUnavailableView.search(text: query).listRowBackground(Color.clear)
            }
            ForEach(hits, id: \.path) { hit in
                NavigationLink(value: Route.note(hit.path)) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(hit.path.noteTitle).font(.system(size: 16, weight: .semibold)).foregroundStyle(Color.dnText)
                        Text(hit.snippet.isEmpty ? (hit.path.parentPath.isEmpty ? "Notes" : hit.path.parentPath) : hit.snippet)
                            .font(.system(size: 14)).foregroundStyle(Color.dnMuted).lineLimit(2)
                    }
                    .padding(.vertical, 3)
                }
                .zoomSource(hit.path)
                .listRowBackground(Color.dnChrome)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .padding(.top, 8)
    }
}
