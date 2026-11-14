import Foundation
import Observation

enum Route: Hashable {
    case folder(String)
    case note(String)
    case image(String)
}

struct PathItem: Identifiable, Hashable {
    let path: String
    var id: String { path }
}

enum NamePrompt: Identifiable {
    case newNote(folder: String)
    case newFolder(parent: String)
    case rename(Entry)

    var id: String {
        switch self {
        case .newNote(let f): "note:\(f)"
        case .newFolder(let p): "folder:\(p)"
        case .rename(let e): "rename:\(e.path)"
        }
    }

    var title: String {
        switch self {
        case .newNote: "New Note"
        case .newFolder: "New Folder"
        case .rename(let e): e.isFolder ? "Rename Folder" : "Rename Note"
        }
    }

    var placeholder: String {
        if case .newFolder = self { return "Folder name" }
        return "Untitled"
    }

    var initial: String {
        if case .rename(let e) = self { return e.isFolder || e.isImage ? e.name : e.path.noteTitle }
        return ""
    }
}

/// What the home list shows, chosen with the scope chips.
enum Scope: Hashable {
    case all
    case folder(String)
    case tag(String)
}

enum SyncStatus: Equatable {
    case localOnly
    case idle(Date)
    case syncing
    case failed(String)
}

/// The note open in the editor. `saved` is what is on disk; `text` may be ahead of it.
@Observable
final class EditorSession {
    let path: String
    var text: String
    var saved: String
    /// Bumped when the text is replaced from outside the editor (sync merge).
    var externalRevision = 0
    /// Markdown waiting to be inserted at the cursor (e.g. a picked image).
    var pendingInsert: String?
    /// Put the cursor in the body (after pressing return in the title field).
    var wantsBodyFocus = false

    init(path: String, text: String) {
        self.path = path
        self.text = text
        self.saved = text
    }
}

@MainActor
@Observable
final class NotesModel {
    let vault: Vault
    private(set) var entries: [Entry] = []
    var route: [Route] = []
    private(set) var session: EditorSession?
    private(set) var status: SyncStatus = .localOnly
    var conflicts: [String] = []
    var alert: String?

    // Sheets and dialogs requested from anywhere (rows, note toolbar); presented by RootView.
    var prompt: NamePrompt?
    var tagging: PathItem?
    var deleting: Entry?
    /// Note, image or folder being moved (shows the folder picker).
    var moving: Entry?

    // Pins and tags (cached from .dnote/workspace.json).
    private(set) var pins: [String] = []
    private(set) var tags: [TagInfo] = []
    /// path -> tag ids
    private(set) var tagMap: [String: Set<String>] = [:]
    /// path -> one-line preview (cached by modification time)
    private var previewCache: [String: (Int64, String)] = [:]
    let rootURL: URL

    private(set) var server: String = UserDefaults.standard.string(forKey: "server") ?? ""
    private(set) var token: String = Keychain.get("token") ?? ""
    var isConnected: Bool { !server.isEmpty && !token.isEmpty }

    private var saveTask: Task<Void, Never>?
    private var syncTask: Task<Void, Never>?
    private var timer: Timer?
    private var syncing = false
    private var syncAgain = false

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let root = docs.appendingPathComponent("Notes", isDirectory: true)
        rootURL = root
        do {
            vault = try Vault.open(root: root.path)
        } catch {
            fatalError("Cannot open the notes folder: \(error.userMessage)")
        }
        if !isConnected { status = .localOnly }
        seedIfEmpty()
        reload()
        timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.sync() }
        }
    }

    // MARK: Tree

    func reload() {
        do { entries = try vault.entries() } catch { alert = error.userMessage }
        refreshWorkspace()
    }

    func refreshWorkspace() {
        pins = vault.pins()
        tags = vault.tags()
        var map: [String: Set<String>] = [:]
        for tag in tags {
            for path in vault.pathsWithTag(tagId: tag.id) { map[path, default: []].insert(tag.id) }
        }
        tagMap = map
    }

    func entry(_ path: String) -> Entry? {
        entries.first { $0.path == path }
    }

    func topFolders() -> [Entry] {
        entries.filter { $0.isFolder && $0.parent.isEmpty }
    }

    /// Items for a home scope: subfolders first, then notes, then images.
    func items(for scope: Scope) -> [Entry] {
        switch scope {
        case .all:
            return entries.filter { !$0.isFolder && !$0.isImage }.sorted { $0.modifiedMs > $1.modifiedMs }
        case .folder(let path):
            return ordered(children(of: path))
        case .tag(let id):
            return ordered(entries.filter { tagMap[$0.path]?.contains(id) ?? false })
        }
    }

    func ordered(_ list: [Entry]) -> [Entry] {
        list.filter(\.isFolder) + list.filter { !$0.isFolder && !$0.isImage } + list.filter(\.isImage)
    }

    func preview(_ entry: Entry) -> String {
        if let (ms, text) = previewCache[entry.path], ms == entry.modifiedMs { return text }
        let text = vault.preview(path: entry.path)
        previewCache[entry.path] = (entry.modifiedMs, text)
        return text
    }

    func tagsOf(_ path: String) -> [TagInfo] {
        guard let ids = tagMap[path] else { return [] }
        return tags.filter { ids.contains($0.id) }
    }

    func isPinned(_ path: String) -> Bool { pins.contains(path) }

    /// Which view the open transition zooms from: the pinned card or the list row.
    var zoomFromPinned: String?
    func zoomID(for path: String) -> String { zoomFromPinned == path ? "pin:" + path : path }

    func fileURL(_ path: String) -> URL { rootURL.appendingPathComponent(path) }

    // MARK: Pins & tags

    func togglePin(_ path: String) {
        do {
            _ = try vault.togglePin(path: path)
            refreshWorkspace()
            scheduleSync(after: 1)
        } catch { alert = error.userMessage }
    }

    func setTag(_ path: String, _ tagId: String, _ on: Bool) {
        do {
            try vault.setTag(path: path, tagId: tagId, on: on)
            refreshWorkspace()
            scheduleSync(after: 1)
        } catch { alert = error.userMessage }
    }

    @discardableResult
    func createTag(name: String, color: String) -> TagInfo? {
        do {
            let tag = try vault.createTag(name: name, color: color)
            refreshWorkspace()
            scheduleSync(after: 1)
            return tag
        } catch {
            alert = error.userMessage
            return nil
        }
    }

    func updateTag(_ id: String, name: String, color: String) {
        do {
            try vault.updateTag(id: id, name: name, color: color)
            refreshWorkspace()
            scheduleSync(after: 1)
        } catch { alert = error.userMessage }
    }

    func deleteTag(_ id: String) {
        do {
            try vault.deleteTag(id: id)
            refreshWorkspace()
            scheduleSync(after: 1)
        } catch { alert = error.userMessage }
    }

    // MARK: Images

    /// Save a picked image and queue its Markdown for insertion at the editor's cursor.
    func addImage(_ data: Data, fileName: String) {
        guard let s = session else { return }
        do {
            let markdown = try vault.addImage(notePath: s.path, fileName: fileName, data: data)
            s.pendingInsert = "\n" + markdown + "\n"
            reload()
        } catch { alert = error.userMessage }
    }

    func children(of folder: String) -> [Entry] {
        entries.filter { $0.parent == folder }
    }

    func noteCount(in folder: String) -> Int {
        entries.filter { !$0.isFolder && ($0.parent == folder || $0.parent.hasPrefix(folder + "/")) }.count
    }

    func search(_ query: String) -> [SearchHit] {
        (try? vault.search(query: query)) ?? []
    }

    private func seedIfEmpty() {
        guard (try? vault.entries())?.isEmpty ?? false, !UserDefaults.standard.bool(forKey: "seeded") else { return }
        UserDefaults.standard.set(true, forKey: "seeded")
        try? vault.write(path: "Welcome.md", content: welcomeNote())
    }

    // MARK: Notes

    func open(_ path: String) {
        flushSave()
        guard session?.path != path else { return }
        do {
            session = EditorSession(path: path, text: try vault.read(path: path))
        } catch {
            alert = error.userMessage
            session = nil
        }
    }

    func closeEditor(_ path: String) {
        guard session?.path == path else { return }
        flushSave()
        session = nil
        // Pick up the new modified time so the list shows this note's fresh preview.
        reload()
    }

    /// Called by the editor on every keystroke.
    func edited() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(600))
            guard !Task.isCancelled else { return }
            self?.flushSave()
        }
        scheduleSync(after: 2.5)
    }

    func flushSave() {
        saveTask?.cancel()
        // While a sync runs, edits stay in memory; they are merged in when it finishes.
        guard let s = session, !syncing, s.text != s.saved else { return }
        do {
            try vault.write(path: s.path, content: s.text)
            s.saved = s.text
        } catch {
            alert = error.userMessage
        }
    }

    /// A note just created by "New note": its title field starts focused.
    var titleFocusPath: String?

    /// Create "Untitled note (n)" in `folder` and open it; the name is set from the title field.
    func newNote(in folder: String) {
        guard let path = createNote(in: folder, name: "") else { return }
        titleFocusPath = path
        route.append(.note(path))
    }

    /// Rename the open note from its title field (same folder). Empty or unchanged titles are ignored.
    func renameTitle(of path: String, to title: String) {
        let title = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, title != path.noteTitle, vault.exists(path: path) else { return }
        flushSave()
        do {
            let newPath = try vault.renameNoteTitle(path: path, title: title)
            guard newPath != path else { return }
            if let s = session, s.path == path {
                let next = EditorSession(path: newPath, text: s.text)
                next.wantsBodyFocus = s.wantsBodyFocus
                session = next
            }
            route = route.map { $0 == .note(path) ? .note(newPath) : $0 }
            if titleFocusPath == path { titleFocusPath = nil }
            reload()
            refreshWorkspace()
            scheduleSync(after: 1)
        } catch {
            alert = error.userMessage
        }
    }

    @discardableResult
    func createNote(in folder: String, name: String) -> String? {
        do {
            let path = try vault.createNote(folder: folder, name: name)
            reload()
            scheduleSync(after: 1)
            return path
        } catch {
            alert = error.userMessage
            return nil
        }
    }

    func createFolder(in parent: String, name: String) {
        let path = parent.isEmpty ? name : "\(parent)/\(name)"
        do {
            _ = try vault.createFolder(path: path)
            reload()
        } catch {
            alert = error.userMessage
        }
    }

    func rename(_ entry: Entry, to newName: String) {
        let trimmed = newName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let target = entry.parent.isEmpty ? trimmed : "\(entry.parent)/\(trimmed)"
        flushSave()
        do {
            let newPath = try vault.rename(from: entry.path, to: target)
            if let s = session, s.path == entry.path || s.path.hasPrefix(entry.path + "/") {
                let moved = newPath + s.path.dropFirst(entry.path.count)
                session = EditorSession(path: moved, text: s.text)
            }
            route = route.map { r in
                switch r {
                case .folder(let p) where p == entry.path || p.hasPrefix(entry.path + "/"):
                    return .folder(newPath + p.dropFirst(entry.path.count))
                case .note(let p) where p == entry.path || p.hasPrefix(entry.path + "/"):
                    return .note(newPath + p.dropFirst(entry.path.count))
                default: return r
                }
            }
            reload()
            scheduleSync(after: 1)
        } catch {
            alert = error.userMessage
        }
    }

    /// All folders, depth-first, for the "Move to…" picker.
    var allFolders: [Entry] { entries.filter(\.isFolder) }

    /// Move a note, image or folder into `folder` ("" = top level), keeping its name.
    func move(_ entry: Entry, to folder: String) {
        guard folder != entry.parent, folder != entry.path, !folder.hasPrefix(entry.path + "/") else { return }
        let target = folder.isEmpty ? entry.name : "\(folder)/\(entry.name)"
        flushSave()
        do {
            let newPath = try vault.rename(from: entry.path, to: target)
            func moved(_ p: String) -> String {
                p == entry.path || p.hasPrefix(entry.path + "/") ? newPath + p.dropFirst(entry.path.count) : p
            }
            if let s = session, moved(s.path) != s.path { session = EditorSession(path: moved(s.path), text: s.text) }
            route = route.map { r in
                switch r {
                case .folder(let p): return .folder(moved(p))
                case .note(let p): return .note(moved(p))
                case .image(let p): return .image(moved(p))
                }
            }
            reload()
            refreshWorkspace()
            scheduleSync(after: 1)
        } catch {
            alert = error.userMessage
        }
    }

    func delete(_ path: String) {
        if let s = session, s.path == path || s.path.hasPrefix(path + "/") { session = nil }
        route.removeAll { r in
            switch r {
            case .folder(let p), .note(let p), .image(let p): return p == path || p.hasPrefix(path + "/")
            }
        }
        do {
            try vault.delete(path: path)
            reload()
            scheduleSync(after: 1)
        } catch {
            alert = error.userMessage
        }
    }

    // MARK: Sync

    func appBecameActive() {
        reload() // Files app may have changed the folder.
        sync()
    }

    func scheduleSync(after seconds: Double) {
        guard isConnected else { return }
        syncTask?.cancel()
        syncTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            self?.sync()
        }
    }

    func sync() {
        guard isConnected else { status = .localOnly; return }
        if syncing { syncAgain = true; return }
        flushSave()
        syncing = true
        status = .syncing
        let (vault, server, token) = (vault, server, token)
        Task {
            let result = await Task.detached { () -> Result<SyncSummary, Error> in
                Result { try vault.sync(server: server, token: token) }
            }.value
            syncing = false
            switch result {
            case .success(let summary):
                status = .idle(Date())
                if !summary.conflicts.isEmpty { conflicts = summary.conflicts }
                if !summary.changed.isEmpty {
                    reload()
                    reconcileOpenNote(changed: Set(summary.changed))
                }
            case .failure(let error):
                status = .failed(error.userMessage)
            }
            flushSave()
            if syncAgain {
                syncAgain = false
                sync()
            }
        }
    }

    /// Pull-to-refresh: sync and wait for it.
    func syncAndWait() async {
        sync()
        while syncing { try? await Task.sleep(for: .milliseconds(150)) }
    }

    private func reconcileOpenNote(changed: Set<String>) {
        guard let s = session, changed.contains(s.path) else { return }
        guard let stored = try? vault.read(path: s.path) else {
            session = nil
            route.removeAll { $0 == .note(s.path) }
            alert = "“\(s.path.noteTitle)” was deleted on another device."
            return
        }
        let next = s.text == s.saved ? stored : merge3(base: s.saved, ours: s.text, theirs: stored)
        s.saved = stored
        if next != s.text {
            s.text = next
            s.externalRevision += 1
        }
        if next != stored { edited() }
    }

    // MARK: Settings

    func connect(server: String, token: String) async throws -> String {
        let server = server.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let token = token.trimmingCharacters(in: .whitespacesAndNewlines)
        let backend = try await Task.detached { try checkServer(server: server, token: token) }.value
        if server != self.server { try? vault.resetSync() }
        self.server = server
        self.token = token
        UserDefaults.standard.set(server, forKey: "server")
        Keychain.set("token", token)
        sync()
        return backend
    }

    func disconnect() {
        server = ""
        token = ""
        UserDefaults.standard.removeObject(forKey: "server")
        Keychain.set("token", nil)
        status = .localOnly
    }
}
