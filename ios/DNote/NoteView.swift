import PhotosUI
import SwiftUI

struct NoteView: View {
    let path: String
    @Environment(NotesModel.self) private var model
    @AppStorage("readMode") private var reading = false
    @State private var showPhotos = false
    @State private var photo: PhotosPickerItem?

    var body: some View {
        VStack(spacing: 0) {
            NoteTitleField(path: path)
            content
        }
        .background(Color.dnBg.ignoresSafeArea())
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Color.dnBg, for: .navigationBar)
        .toolbar { toolbar }
        .photosPicker(isPresented: $showPhotos, selection: $photo, matching: .images)
        .onChange(of: photo) { _, item in
            guard let item else { return }
            photo = nil
            Task { await insert(item) }
        }
        .onAppear { model.open(path) }
        .onDisappear { model.closeEditor(path) }
    }

    @ViewBuilder private var content: some View {
        Group {
            if let session = model.session, session.path == path {
                if reading {
                    MarkdownPreview(markdown: session.text, notePath: path, root: model.rootURL) { target in
                        if model.entry(target)?.isImage == true {
                            model.route.append(.image(target))
                        } else if model.entry(target) != nil {
                            model.route.append(.note(target))
                        } else {
                            model.alert = "No note at \(target)"
                        }
                    }
                    .ignoresSafeArea(edges: .bottom)
                } else {
                    MarkdownTextView(session: session, onEdit: { model.edited() }, onPickImage: { showPhotos = true })
                        .ignoresSafeArea(edges: .bottom)
                }
            } else {
                Color.clear
            }
        }
        .frame(maxHeight: .infinity)
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
            ToolbarItem(placement: .principal) {
                Picker("Mode", selection: $reading.animation(.easeInOut(duration: 0.2))) {
                    Text("Write").tag(false)
                    Text("Read").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(width: 128)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    withAnimation(.snappy) { model.togglePin(path) }
                } label: {
                    Image(systemName: model.isPinned(path) ? "pin.fill" : "pin")
                        .contentTransition(.symbolEffect(.replace))
                }
                .accessibilityLabel(model.isPinned(path) ? "Unpin" : "Pin")
                Button { model.tagging = PathItem(path: path) } label: {
                    Image(systemName: model.tagsOf(path).isEmpty ? "tag" : "tag.fill")
                }
                .accessibilityLabel("Tags")
                Menu {
                    ShareLink(item: model.session?.text ?? "") { Label("Share", systemImage: "square.and.arrow.up") }
                    Button { showPhotos = true } label: { Label("Add Image", systemImage: "photo") }
                    if let entry = model.entry(path) {
                        Button { model.prompt = .rename(entry) } label: { Label("Rename", systemImage: "pencil") }
                        Divider()
                        Button(role: .destructive) { model.deleting = entry } label: { Label("Delete", systemImage: "trash") }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel("More")
            }
    }

    /// Load the picked photo, downscale to 2560 px, encode as JPEG and add it to the note.
    private func insert(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            model.alert = "Couldn't load that photo."
            return
        }
        let jpeg = await Task.detached(priority: .userInitiated) { () -> Data? in
            guard let image = UIImage(data: data) else { return nil }
            return ImageEncoding.jpeg(image, maxDimension: 2560, quality: 0.85)
        }.value
        guard let jpeg else {
            model.alert = "That file isn't an image DNote can read."
            return
        }
        if reading { reading = false }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HHmmss"
        model.addImage(jpeg, fileName: "Photo \(f.string(from: Date())).jpg")
    }
}

enum ImageEncoding {
    static func jpeg(_ image: UIImage, maxDimension: CGFloat, quality: CGFloat) -> Data? {
        let size = image.size
        let scale = min(1, maxDimension / max(size.width, size.height))
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let resized = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        return resized.jpegData(compressionQuality: quality)
    }
}


/// The note's name, edited in place. Committing (return or leaving the field) renames the file;
/// an empty field keeps the current name. New "Untitled note (n)" notes start with an empty
/// field showing that name as the placeholder, so typing just names the note.
struct NoteTitleField: View {
    let path: String
    @Environment(NotesModel.self) private var model
    @State private var text = ""
    @FocusState private var focused: Bool

    private var isUntitled: Bool { path.noteTitle.hasPrefix("Untitled note (") }

    var body: some View {
        TextField(path.noteTitle, text: $text, axis: .vertical)
            .font(.system(size: 28, weight: .bold))
            .tracking(-0.6)
            .foregroundStyle(Color.dnText)
            .lineLimit(1...3)
            .submitLabel(.done)
            .focused($focused)
            .padding(.horizontal, 19)
            .padding(.top, 8)
            .padding(.bottom, 2)
            .onChange(of: text) { _, new in
                // Return in a vertical field inserts a newline: treat it as "done".
                if new.contains("\n") {
                    text = new.replacingOccurrences(of: "\n", with: "")
                    model.session?.wantsBodyFocus = true
                    focused = false
                }
            }
            .onChange(of: focused) { _, isFocused in
                if !isFocused { commit() }
            }
            .onAppear {
                text = isUntitled ? "" : path.noteTitle
                if model.titleFocusPath == path {
                    model.titleFocusPath = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { focused = true }
                }
            }
            .onDisappear { commit() }
            .accessibilityLabel("Note title")
    }

    private func commit() {
        let title = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if title.isEmpty {
            text = isUntitled ? "" : path.noteTitle
            return
        }
        model.renameTitle(of: path, to: title)
    }
}
