import SwiftUI

struct SettingsView: View {
    @Environment(NotesModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var server = ""
    @State private var token = ""
    @State private var checking = false
    @State private var message: (String, Bool)?
    @AppStorage("theme") private var theme: AppTheme = .system
    @State private var editingTag: TagInfo?
    @State private var newTag = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://notes.example.com", text: $server)
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Access token", text: $token)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button {
                        connect()
                    } label: {
                        HStack {
                            Text(model.isConnected ? "Save & Sync" : "Connect")
                            if checking { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(checking || server.isEmpty || token.isEmpty)
                } header: {
                    Text("Sync server")
                } footer: {
                    if let (text, ok) = message {
                        Text(text).foregroundStyle(ok ? Color.dnSuccess : .red)
                    } else {
                        Text("Optional. A self-hosted dnote-server or your Cloudflare Worker. Notes always stay on this device too.")
                    }
                }

                Section("Local development") {
                    quickFill("Simulator → this Mac", "http://localhost:8787")
                    quickFill("Simulator → wrangler dev", "http://localhost:8898")
                    Text("On a real iPhone, use your Mac's LAN address, e.g. http://192.168.1.20:8787, and run the server with DNOTE_ADDR=0.0.0.0:8787.")
                        .font(.footnote).foregroundStyle(.secondary)
                }

                if model.isConnected {
                    Section {
                        LabeledContent("Status") { SyncBadge() }
                        Button("Sync Now") { model.sync() }
                        Button("Disconnect", role: .destructive) {
                            model.disconnect()
                            message = ("Disconnected. Notes stay on this device.", true)
                        }
                    }
                    if case .failed(let err) = model.status {
                        Section("Last error") { Text(err).font(.footnote).foregroundStyle(.red) }
                    }
                }

                Section {
                    ForEach(model.tags, id: \.id) { tag in
                        Button {
                            if tag.id != "favorite" { editingTag = tag }
                        } label: {
                            HStack(spacing: 12) {
                                TagDot(tag: tag, size: 10).frame(width: 20)
                                Text(tag.name).foregroundStyle(Color.dnText)
                                Spacer()
                                Text("\(tag.count)").monospacedDigit().foregroundStyle(Color.dnFaint)
                                if tag.id != "favorite" {
                                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Color.dnFaint)
                                }
                            }
                        }
                        .deleteDisabled(tag.id == "favorite")
                    }
                    .onDelete { offsets in
                        for i in offsets where model.tags[i].id != "favorite" { model.deleteTag(model.tags[i].id) }
                    }
                    Button { newTag = true } label: { Label("New Tag", systemImage: "plus") }
                } header: {
                    Text("Tags")
                } footer: {
                    Text("Favorite is built in. Tags and pins sync to your other devices.")
                }

                Section("Appearance") {
                    Picker("Theme", selection: $theme) {
                        ForEach(AppTheme.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }

                Section("Storage") {
                    LabeledContent("Notes", value: "\(model.entries.filter { !$0.isFolder }.count)")
                    Text("Files app › On My iPhone › DNote › Notes").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(item: $editingTag) { tag in TagEditor(tag: tag) }
            .sheet(isPresented: $newTag) { TagEditor(tag: nil) }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .onAppear {
                server = model.server
                token = model.token
            }
        }
    }

    private func quickFill(_ title: String, _ url: String) -> some View {
        Button {
            server = url
        } label: {
            LabeledContent(title) { Text(url).font(.footnote.monospaced()).foregroundStyle(.secondary) }
        }
        .foregroundStyle(.primary)
    }

    private func connect() {
        checking = true
        message = nil
        Task {
            do {
                let backend = try await model.connect(server: server, token: token)
                message = ("Connected (\(backend)). Syncing…", true)
            } catch {
                message = (error.userMessage, false)
            }
            checking = false
        }
    }
}


/// Create a tag, or rename / recolour / delete an existing one.
struct TagEditor: View {
    let tag: TagInfo?
    @Environment(NotesModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var color = "blue"
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Tag name", text: $name).focused($focused).submitLabel(.done).onSubmit(save)
                }
                Section("Colour") { ColorRow(selection: $color) }
                if let tag {
                    Section {
                        Button("Delete Tag", role: .destructive) {
                            model.deleteTag(tag.id)
                            dismiss()
                        }
                    } footer: {
                        Text("Removes the tag from \(tag.count) item\(tag.count == 1 ? "" : "s"). The notes themselves are kept.")
                    }
                }
            }
            .navigationTitle(tag == nil ? "New Tag" : "Edit Tag")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save).fontWeight(.semibold)
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
        .onAppear {
            name = tag?.name ?? ""
            color = tag?.color ?? "blue"
            focused = tag == nil
        }
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        if let tag {
            model.updateTag(tag.id, name: trimmed, color: color)
        } else {
            model.createTag(name: trimmed, color: color)
        }
        dismiss()
    }
}
