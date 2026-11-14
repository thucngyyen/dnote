import SwiftUI

/// A subfolder, reached by drilling in from the home list.
struct FolderView: View {
    let folder: String
    @Environment(NotesModel.self) private var model

    var body: some View {
        let items = model.ordered(model.children(of: folder))
        List {
            if items.isEmpty { EmptyScope(scope: .folder(folder)) }
            ForEach(items, id: \.path) { entry in
                EntryRow(entry: entry)
                    .listRowBackground(Color.dnChrome)
                    .listRowSeparatorTint(Color.dnBorder)
                    .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
            }
            Color.clear.frame(height: 70).listRowBackground(Color.clear).listRowSeparator(.hidden)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.dnChrome.ignoresSafeArea())
        .refreshable { await model.syncAndWait() }
        .navigationTitle((folder as NSString).lastPathComponent)
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { model.togglePin(folder) } label: {
                    Image(systemName: model.isPinned(folder) ? "pin.fill" : "pin")
                }
                .accessibilityLabel(model.isPinned(folder) ? "Unpin folder" : "Pin folder")
                Menu {
                    Button { model.prompt = .newFolder(parent: folder) } label: { Label("New Folder", systemImage: "folder.badge.plus") }
                    if let entry = model.entry(folder) { EntryMenu(entry: entry) }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            Button { model.newNote(in: folder) } label: {
                Label("New note", systemImage: "square.and.pencil")
            }
            .buttonStyle(PrimaryButtonStyle())
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 4)
            .background(LinearGradient(colors: [Color.dnChrome.opacity(0), Color.dnChrome], startPoint: .top, endPoint: .center))
        }
    }
}

// MARK: - Name prompt

struct NamePromptView: View {
    let prompt: NamePrompt
    let onSubmit: (String) -> Void
    @State private var name = ""
    @FocusState private var focused: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                TextField(prompt.placeholder, text: $name)
                    .focused($focused)
                    .submitLabel(.done)
                    .onSubmit(submit)
                    .autocorrectionDisabled()
            }
            .navigationTitle(prompt.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Done", action: submit).fontWeight(.semibold) }
            }
        }
        .onAppear {
            name = prompt.initial
            focused = true
        }
    }

    private func submit() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if case .newFolder = prompt, trimmed.isEmpty { return }
        dismiss()
        onSubmit(trimmed)
    }
}

// MARK: - Tag picker

/// Check tags on and off for one note or folder; create new tags inline.
struct TagPicker: View {
    let path: String
    @Environment(NotesModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var creating = false
    @State private var newName = ""
    @State private var newColor = "blue"
    @FocusState private var nameFocused: Bool

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(model.tags, id: \.id) { tag in
                        let on = model.tagsOf(path).contains { $0.id == tag.id }
                        Button {
                            withAnimation(.snappy) { model.setTag(path, tag.id, !on) }
                        } label: {
                            HStack(spacing: 12) {
                                TagDot(tag: tag, size: 10).frame(width: 20)
                                Text(tag.name).foregroundStyle(Color.dnText)
                                Spacer()
                                Image(systemName: on ? "checkmark.circle.fill" : "circle")
                                    .font(.system(size: 20))
                                    .foregroundStyle(on ? Color.dnAccent : Color.dnFaint)
                                    .contentTransition(.symbolEffect(.replace))
                            }
                        }
                    }
                } header: {
                    Text(path.noteTitle)
                }
                Section {
                    if creating {
                        TextField("Tag name", text: $newName)
                            .focused($nameFocused)
                            .submitLabel(.done)
                            .onSubmit(create)
                        ColorRow(selection: $newColor)
                        Button("Add Tag", action: create)
                            .fontWeight(.semibold)
                            .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                    } else {
                        Button {
                            withAnimation { creating = true }
                            nameFocused = true
                        } label: {
                            Label("New tag…", systemImage: "plus")
                        }
                    }
                }
            }
            .navigationTitle("Tags")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.fontWeight(.semibold) }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func create() {
        let name = newName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty, let tag = model.createTag(name: name, color: newColor) else { return }
        model.setTag(path, tag.id, true)
        newName = ""
        withAnimation { creating = false }
    }
}

/// Pick one of the tag colours.
struct ColorRow: View {
    @Binding var selection: String

    var body: some View {
        HStack(spacing: 12) {
            ForEach(tagColors(), id: \.self) { name in
                Button { selection = name } label: {
                    Circle()
                        .fill(Color.dnTag(name))
                        .frame(width: 26, height: 26)
                        .overlay(Circle().stroke(Color.dnText, lineWidth: selection == name ? 2 : 0).padding(-3))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(name.capitalized)
                .accessibilityAddTraits(selection == name ? .isSelected : [])
            }
        }
        .padding(.vertical, 6)
    }
}

// MARK: - Move to folder

extension Entry: Identifiable {
    public var id: String { path }
}

struct MovePicker: View {
    let entry: Entry
    @Environment(NotesModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var newFolder = false
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                row(path: "", name: "Top level", depth: 0, icon: "tray.full")
                ForEach(model.allFolders.filter { $0.path != entry.path && !$0.path.hasPrefix(entry.path + "/") }, id: \.path) { f in
                    row(path: f.path, name: f.name, depth: Int(f.depth) + 1, icon: "folder")
                }
                Section {
                    if newFolder {
                        HStack {
                            Image(systemName: "folder.badge.plus").foregroundStyle(Color.dnAccent)
                            TextField("Folder name", text: $newName)
                                .submitLabel(.done)
                                .onSubmit(createAndMove)
                            Button("Create", action: createAndMove).disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                        }
                    } else {
                        Button { withAnimation { newFolder = true } } label: {
                            Label("New Folder…", systemImage: "folder.badge.plus")
                        }
                    }
                }
            }
            .navigationTitle("Move “\(entry.isFolder || entry.isImage ? entry.name : entry.path.noteTitle)”")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .tint(Color.dnAccent)
    }

    private func row(path: String, name: String, depth: Int, icon: String) -> some View {
        let current = path == entry.parent
        return Button {
            dismiss()
            withAnimation { model.move(entry, to: path) }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: icon).foregroundStyle(current ? Color.dnFaint : Color.dnAccent).frame(width: 22)
                Text(name).foregroundStyle(current ? Color.dnFaint : Color.dnText)
                Spacer()
                if current { Text("Current").font(.footnote).foregroundStyle(Color.dnFaint) }
            }
            .padding(.leading, CGFloat(max(0, depth - 1)) * 18)
        }
        .disabled(current)
    }

    private func createAndMove() {
        let name = newName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        model.createFolder(in: "", name: name)
        dismiss()
        withAnimation { model.move(entry, to: name) }
    }
}

// MARK: - Root-level sheets for row / toolbar actions

struct ActionSheets: ViewModifier {
    @Environment(NotesModel.self) private var model

    func body(content: Content) -> some View {
        @Bindable var model = model
        content
            .sheet(item: $model.prompt) { prompt in
                NamePromptView(prompt: prompt) { name in handle(prompt, name) }
                    .presentationDetents([.height(220)])
            }
            .sheet(item: $model.tagging) { item in TagPicker(path: item.path) }
            .sheet(item: $model.moving) { entry in MovePicker(entry: entry) }
            .confirmationDialog(
                model.deleting.map { "Delete “\($0.isFolder || $0.isImage ? $0.name : $0.path.noteTitle)”?" } ?? "",
                isPresented: Binding(get: { model.deleting != nil }, set: { if !$0 { model.deleting = nil } }),
                titleVisibility: .visible
            ) {
                Button("Delete", role: .destructive) {
                    if let e = model.deleting { withAnimation { model.delete(e.path) } }
                }
            } message: {
                if let e = model.deleting, e.isFolder {
                    Text("The folder and its \(model.noteCount(in: e.path)) notes will be deleted on all synced devices.")
                } else {
                    Text("It will be deleted on all synced devices.")
                }
            }
    }

    private func handle(_ prompt: NamePrompt, _ name: String) {
        switch prompt {
        case .newNote(let folder):
            if let path = model.createNote(in: folder, name: name) { model.route.append(.note(path)) }
        case .newFolder(let parent):
            if !name.isEmpty { withAnimation { model.createFolder(in: parent, name: name) } }
        case .rename(let entry):
            model.rename(entry, to: name)
        }
    }
}

extension View {
    func actionSheets() -> some View { modifier(ActionSheets()) }
}
