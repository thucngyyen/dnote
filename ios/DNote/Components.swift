import SwiftUI
import UIKit

// MARK: - List rows

/// A folder, note or image row with preview, date, tag dots and pin glyph.
/// Long-press: context menu. Swipe: pin / delete (when inside a List).
struct EntryRow: View {
    let entry: Entry
    var showFolder = false
    @Environment(NotesModel.self) private var model

    var body: some View {
        NavigationLink(value: route) {
            HStack(alignment: entry.isFolder ? .center : .top, spacing: 12) {
                icon
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(title)
                            .font(.system(size: 16, weight: entry.isFolder ? .medium : .semibold))
                            .tracking(-0.2)
                            .foregroundStyle(Color.dnText)
                            .lineLimit(1)
                        if model.isPinned(entry.path) {
                            Image(systemName: "pin.fill")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(Color.dnAccent)
                                .rotationEffect(.degrees(45))
                        }
                        Spacer(minLength: 6)
                        if entry.isFolder {
                            Text("\(model.noteCount(in: entry.path))")
                                .font(.system(size: 13)).monospacedDigit()
                                .foregroundStyle(Color.dnFaint)
                        } else {
                            Text(shortDate(entry.modifiedMs))
                                .font(.system(size: 12)).monospacedDigit()
                                .foregroundStyle(Color.dnFaint)
                        }
                    }
                    if !entry.isFolder {
                        HStack(spacing: 6) {
                            let tags = model.tagsOf(entry.path)
                            if !tags.isEmpty {
                                HStack(spacing: 3) { ForEach(tags, id: \.id) { TagDot(tag: $0, size: 6) } }
                            }
                            Text(subtitle)
                                .font(.system(size: 14))
                                .foregroundStyle(Color.dnMuted)
                                .lineLimit(1)
                        }
                    } else if !model.tagsOf(entry.path).isEmpty {
                        HStack(spacing: 3) { ForEach(model.tagsOf(entry.path), id: \.id) { TagDot(tag: $0, size: 6) } }
                    }
                }
            }
            .padding(.vertical, entry.isFolder ? 6 : 4)
            .contentShape(Rectangle())
        }
        .zoomSource(entry.path)
        .contextMenu { EntryMenu(entry: entry) }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) { model.deleting = entry } label: { Label("Delete", systemImage: "trash") }
            if !entry.isImage {
                Button { model.tagging = PathItem(path: entry.path) } label: { Label("Tags", systemImage: "tag") }
                    .tint(Color(uiColor: .dnTag("violet")))
            }
        }
        .swipeActions(edge: .leading) {
            Button { model.togglePin(entry.path) } label: {
                Label(model.isPinned(entry.path) ? "Unpin" : "Pin", systemImage: model.isPinned(entry.path) ? "pin.slash" : "pin")
            }
            .tint(.dnAccent)
        }
    }

    private var route: Route {
        entry.isFolder ? .folder(entry.path) : entry.isImage ? .image(entry.path) : .note(entry.path)
    }

    private var title: String {
        entry.isFolder || entry.isImage ? entry.name : entry.path.noteTitle
    }

    private var subtitle: String {
        if entry.isImage { return entry.parent.isEmpty ? "Image" : entry.parent }
        let preview = model.preview(entry)
        if showFolder, !entry.parent.isEmpty { return preview.isEmpty ? entry.parent : "\(entry.parent) · \(preview)" }
        return preview.isEmpty ? "No additional text" : preview
    }

    @ViewBuilder private var icon: some View {
        if entry.isImage {
            Thumbnail(url: model.fileURL(entry.path), size: 44)
        } else if entry.isFolder {
            Image(systemName: "folder.fill")
                .font(.system(size: 17))
                .foregroundStyle(Color.dnAccent)
                .frame(width: 34, height: 34)
                .background(Color.dnAccentSoft, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
        } else {
            Image(systemName: "doc.text")
                .font(.system(size: 15))
                .foregroundStyle(Color.dnMuted)
                .frame(width: 34, height: 34)
                .background(Color.dnChrome, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
        }
    }
}

/// Pin / Tags / Rename / Delete, shared by context menus and the note's "more" menu.
struct EntryMenu: View {
    let entry: Entry
    @Environment(NotesModel.self) private var model

    var body: some View {
        if entry.isFolder {
            Button { model.newNote(in: entry.path) } label: { Label("New Note Here", systemImage: "square.and.pencil") }
            Button { model.prompt = .newFolder(parent: entry.path) } label: { Label("New Subfolder", systemImage: "folder.badge.plus") }
            Divider()
        }
        Button { model.togglePin(entry.path) } label: {
            Label(model.isPinned(entry.path) ? "Unpin" : "Pin", systemImage: model.isPinned(entry.path) ? "pin.slash" : "pin")
        }
        if !entry.isImage {
            Button { model.tagging = PathItem(path: entry.path) } label: { Label("Tags…", systemImage: "tag") }
        }
        Button { model.prompt = .rename(entry) } label: { Label("Rename", systemImage: "pencil") }
        Button { model.moving = entry } label: { Label("Move to…", systemImage: "folder") }
        Divider()
        Button(role: .destructive) { model.deleting = entry } label: { Label("Delete", systemImage: "trash") }
    }
}

// MARK: - Pinned cards

struct PinnedCard: View {
    let entry: Entry
    @Environment(NotesModel.self) private var model

    var body: some View {
        NavigationLink(value: entry.isFolder ? Route.folder(entry.path) : entry.isImage ? Route.image(entry.path) : Route.note(entry.path)) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Image(systemName: entry.isFolder ? "folder.fill" : entry.isImage ? "photo" : "doc.text.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(entry.isFolder ? Color.dnAccent : Color.dnMuted)
                    Spacer()
                    Image(systemName: "pin.fill")
                        .font(.system(size: 9, weight: .bold))
                        .rotationEffect(.degrees(45))
                        .foregroundStyle(Color.dnAccent)
                }
                Spacer(minLength: 0)
                Text(entry.isFolder || entry.isImage ? entry.name : entry.path.noteTitle)
                    .font(.system(size: 15, weight: .semibold))
                    .tracking(-0.2)
                    .foregroundStyle(Color.dnText)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundStyle(Color.dnMuted)
                    .lineLimit(1)
            }
            .padding(12)
            .frame(width: 148, height: 96, alignment: .leading)
            .background(Color.dnSurface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Color.dnBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .zoomSource("pin:" + entry.path)
        .simultaneousGesture(TapGesture().onEnded { model.zoomFromPinned = entry.path })
        .contextMenu { EntryMenu(entry: entry) }
    }

    private var subtitle: String {
        if entry.isFolder {
            let n = model.noteCount(in: entry.path)
            return n == 1 ? "1 note" : "\(n) notes"
        }
        let p = model.preview(entry)
        return p.isEmpty ? shortDate(entry.modifiedMs) : p
    }
}

// MARK: - Scope chips with a sliding pill

struct ScopeChip: Identifiable, Equatable {
    let scope: Scope
    let label: String
    var icon: String?
    var tag: TagInfo?
    /// Folder chips carry their entry for the long-press menu (pin, tags, rename, delete).
    var entry: Entry?
    var id: Scope { scope }
}

struct ScopeChips: View {
    let chips: [ScopeChip]
    @Binding var selection: Scope
    var onSelect: (Scope) -> Void
    @Namespace private var pill
    @Environment(NotesModel.self) private var model

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(chips) { chip in
                        let selected = chip.scope == selection
                        Button {
                            onSelect(chip.scope)
                            withAnimation(.spring(response: 0.3)) { proxy.scrollTo(chip.id, anchor: .center) }
                        } label: {
                            HStack(spacing: 6) {
                                if let tag = chip.tag {
                                    if tag.id == "favorite" {
                                        Image(systemName: "star.fill").font(.system(size: 11, weight: .bold))
                                            .foregroundStyle(selected ? .white : Color.dnTag(tag.color))
                                    } else {
                                        Circle().fill(selected ? .white : Color.dnTag(tag.color)).frame(width: 7, height: 7)
                                    }
                                } else if let icon = chip.icon {
                                    Image(systemName: icon).font(.system(size: 12, weight: .semibold))
                                }
                                Text(chip.label).font(.system(size: 14, weight: selected ? .semibold : .medium))
                            }
                            .foregroundStyle(selected ? Color.white : Color.dnText2)
                            .padding(.horizontal, 14)
                            .frame(height: 34)
                            .background {
                                if selected {
                                    Capsule()
                                        .fill(Color.dnAccent)
                                        .shadow(color: Color.dnAccent.opacity(0.3), radius: 6, y: 2)
                                        .matchedGeometryEffect(id: "pill", in: pill)
                                } else {
                                    Capsule().fill(Color.dnSurface)
                                        .overlay(Capsule().stroke(Color.dnBorder, lineWidth: 1))
                                }
                            }
                            .contentShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            if let entry = chip.entry {
                                EntryMenu(entry: entry)
                            } else if let tag = chip.tag, tag.id != "favorite" {
                                Button(role: .destructive) { model.deleteTag(tag.id) } label: { Label("Delete Tag", systemImage: "trash") }
                            }
                        }
                        .id(chip.id)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
            }
        }
    }
}

// MARK: - Images

private let thumbnailCache = NSCache<NSString, UIImage>()

/// Downsampled image from disk, cached.
struct Thumbnail: View {
    let url: URL
    var size: CGFloat = 44
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Color.dnChrome)
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "photo").foregroundStyle(Color.dnFaint)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
        .task(id: url) { image = await Self.load(url, maxPixel: size * 3) }
    }

    static func load(_ url: URL, maxPixel: CGFloat) async -> UIImage? {
        let key = "\(url.path)#\(Int(maxPixel))" as NSString
        if let hit = thumbnailCache.object(forKey: key) { return hit }
        let image = await Task.detached(priority: .utility) { () -> UIImage? in
            guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
            let opts: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: maxPixel,
                kCGImageSourceCreateThumbnailWithTransform: true,
            ]
            guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
            return UIImage(cgImage: cg)
        }.value
        if let image { thumbnailCache.setObject(image, forKey: key) }
        return image
    }
}

/// Full-screen image with pinch to zoom.
struct ImageViewer: View {
    let path: String
    @Environment(NotesModel.self) private var model
    @State private var image: UIImage?
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(scale)
                    .gesture(
                        MagnificationGesture()
                            .onChanged { scale = max(1, lastScale * $0) }
                            .onEnded { _ in lastScale = scale }
                    )
                    .onTapGesture(count: 2) {
                        withAnimation(.spring) { scale = scale > 1 ? 1 : 2.5; lastScale = scale }
                    }
            } else {
                ProgressView().tint(.white)
            }
        }
        .navigationTitle((path as NSString).lastPathComponent)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.black, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let image { ShareLink(item: Image(uiImage: image), preview: SharePreview(path.noteTitle, image: Image(uiImage: image))) }
            }
        }
        .task { image = await Thumbnail.load(model.fileURL(path), maxPixel: 3000) }
    }
}
