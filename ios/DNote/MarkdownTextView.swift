import SwiftUI
import UIKit

/// Native TextKit editor with a live preview: the note stays Markdown, but syntax is hidden on
/// every line except the one being edited, bullets and task boxes are drawn (tap a box to tick
/// it), and Enter continues lists. Plus a formatting bar above the keyboard.
struct MarkdownTextView: UIViewRepresentable {
    let session: EditorSession
    let onEdit: () -> Void
    var onPickImage: () -> Void = {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UITextView {
        let tv = MarkdownUITextView.make()
        tv.delegate = context.coordinator
        tv.onToggleTask = { [weak coordinator = context.coordinator] range in coordinator?.toggleTask(range) }
        tv.backgroundColor = .dnBg
        tv.tintColor = .dnAccent
        tv.alwaysBounceVertical = true
        tv.keyboardDismissMode = .interactive
        tv.textContainerInset = UIEdgeInsets(top: 18, left: 16, bottom: 120, right: 16)
        tv.autocapitalizationType = .sentences
        tv.smartDashesType = .no
        tv.smartQuotesType = .no
        tv.typingAttributes = MarkdownHighlighter.baseAttributes
        tv.inputAccessoryView = context.coordinator.makeAccessoryBar()
        tv.attributedText = MarkdownHighlighter.highlight(session.text)
        context.coordinator.revision = session.externalRevision
        // Short notes open ready to type; new "Untitled note (n)" notes start in the title field instead.
        if session.wantsBodyFocus || (session.text.count < 30 && !session.path.noteTitle.hasPrefix("Untitled note (")) {
            session.wantsBodyFocus = false
            DispatchQueue.main.async { tv.becomeFirstResponder() }
        }
        context.coordinator.textView = tv
        return tv
    }

    func updateUIView(_ tv: UITextView, context: Context) {
        context.coordinator.parent = self
        // Text replaced by a sync merge: refresh, keeping the cursor nearby.
        if context.coordinator.revision != session.externalRevision {
            context.coordinator.revision = session.externalRevision
            let selected = tv.selectedRange
            tv.attributedText = MarkdownHighlighter.highlight(session.text)
            let len = (session.text as NSString).length
            tv.selectedRange = NSRange(location: min(selected.location, len), length: 0)
            context.coordinator.restyle(tv)
        }
        if session.wantsBodyFocus {
            session.wantsBodyFocus = false
            DispatchQueue.main.async { tv.becomeFirstResponder() }
        }
        // Markdown queued by the model (a picked image): insert at the cursor.
        if let insert = session.pendingInsert {
            DispatchQueue.main.async {
                guard session.pendingInsert != nil else { return }
                session.pendingInsert = nil
                let range = tv.selectedTextRange ?? tv.textRange(from: tv.endOfDocument, to: tv.endOfDocument)!
                tv.replace(range, withText: insert)
                context.coordinator.textViewDidChange(tv)
                tv.scrollRangeToVisible(tv.selectedRange)
            }
        }
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: MarkdownTextView
        var revision = 0
        weak var textView: UITextView?
        /// The paragraph shown as raw Markdown (the one with the cursor), nil when not editing.
        private var activeRange: NSRange?
        /// Where the cursor sits relative to the active line's list marker (see `markerKey`).
        private var cursorKey = 0
        private var restyling = false

        init(_ parent: MarkdownTextView) {
            self.parent = parent
            super.init()
            NotificationCenter.default.addObserver(self, selector: #selector(keyboardChanged(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
            NotificationCenter.default.addObserver(self, selector: #selector(keyboardChanged(_:)), name: UIResponder.keyboardWillHideNotification, object: nil)
        }

        /// Keep the text (and the caret) clear of the keyboard and its formatting bar.
        @objc private func keyboardChanged(_ note: Notification) {
            guard let tv = textView, let window = tv.window else { return }
            var overlap: CGFloat = 0
            if note.name != UIResponder.keyboardWillHideNotification,
               let frame = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue {
                let mine = tv.convert(tv.bounds, to: window)
                overlap = max(0, mine.maxY - frame.minY)
            }
            tv.contentInset.bottom = overlap
            tv.verticalScrollIndicatorInsets.bottom = overlap
            if overlap > 0 { DispatchQueue.main.async { Self.revealCaret(tv) } }
        }

        /// Text changed by the edit in progress (from `shouldChangeTextIn`), and whether it
        /// touched backticks, which can open or close a code fence and restyle everything after.
        private var pendingEdit: NSRange?
        private var pendingFull = false

        /// Re-style for the current cursor line. Only the lines that can have changed are
        /// restyled: the edited text (`edited`), the line the cursor left and the line it's on.
        /// `full` (or an edit of unknown extent) restyles the whole note.
        func restyle(_ tv: UITextView, edited: NSRange? = nil, full: Bool = false) {
            guard !restyling else { return }
            restyling = true
            defer { restyling = false }
            let selected = tv.selectedRange
            let ns = tv.text as NSString
            let previous = activeRange
            activeRange = tv.isFirstResponder ? ns.paragraphRange(for: selected) : nil
            cursorKey = activeRange.map { Self.markerKey(ns, line: $0, cursor: selected.location) } ?? 0
            let cursor = tv.isFirstResponder ? selected.location : nil
            if full {
                MarkdownHighlighter.apply(to: tv.textStorage, active: activeRange, cursor: cursor)
            } else {
                var regions = [previous, activeRange].compactMap { $0 }
                if let edited { regions.append(edited) }
                if regions.isEmpty { regions.append(NSRange(location: min(selected.location, ns.length), length: 0)) }
                for r in regions {
                    MarkdownHighlighter.apply(to: tv.textStorage, active: activeRange, cursor: cursor, region: r)
                }
            }
            if tv.selectedRange != selected { tv.selectedRange = selected }
            tv.typingAttributes = MarkdownHighlighter.baseAttributes
        }

        /// The cursor's column, capped at the end of the line's list/quote marker: positions
        /// past the marker all render the same, so moving among them needs no restyle.
        static func markerKey(_ ns: NSString, line: NSRange, cursor: Int) -> Int {
            let text = ns.substring(with: line)
            let marker = MarkdownHighlighter.markerLength(text)
            return min(cursor - line.location, marker)
        }

        func textViewDidChange(_ tv: UITextView) {
            // While composing (Japanese, Chinese, ...) leave the marked text alone; the commit
            // triggers another change.
            if tv.markedTextRange == nil {
                // An edit we didn't see coming (made on the text storage directly) restyles it all.
                let edited = pendingEdit
                let ns = tv.text as NSString
                let fence = edited.map { e -> Bool in
                    let loc = min(e.location, ns.length)
                    return ns.substring(with: ns.paragraphRange(for: NSRange(location: loc, length: min(e.length, ns.length - loc)))).contains("```")
                } ?? true
                restyle(tv, edited: edited, full: edited == nil || pendingFull || fence)
                pendingEdit = nil
                pendingFull = false
            }
            parent.session.text = tv.text
            parent.onEdit()
            // Re-styling resets layout, which can stop UITextView from following the caret.
            DispatchQueue.main.async { Self.revealCaret(tv) }
        }

        static func revealCaret(_ tv: UITextView) {
            guard tv.isFirstResponder, let end = tv.selectedTextRange?.end else { return }
            let caret = tv.caretRect(for: end).insetBy(dx: 0, dy: -12)
            tv.scrollRectToVisible(caret, animated: false)
        }

        func textViewDidChangeSelection(_ tv: UITextView) {
            guard !restyling, tv.markedTextRange == nil else { return }
            let ns = tv.text as NSString
            let selected = tv.selectedRange
            let paragraph = tv.isFirstResponder ? ns.paragraphRange(for: selected) : nil
            // Only the cursor's line matters, and where it is relative to that line's marker.
            let key = paragraph.map { Self.markerKey(ns, line: $0, cursor: selected.location) } ?? 0
            if paragraph != activeRange || key != cursorKey { restyle(tv) }
        }

        func textViewShouldBeginEditing(_ tv: UITextView) -> Bool {
            !((tv as? MarkdownUITextView)?.touchOnTask ?? false)
        }

        func textViewDidBeginEditing(_ tv: UITextView) {
            restyle(tv)
            // After the keyboard has resized the editor.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { Self.revealCaret(tv) }
        }
        func textViewDidEndEditing(_ tv: UITextView) { restyle(tv) }

        /// Enter continues lists, quotes and task lists; on an empty item it ends the list.
        func textView(_ tv: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
            // Remember what changes, so textViewDidChange restyles just those lines.
            let replaced = (tv.text as NSString).substring(with: range)
            let changed = NSRange(location: range.location, length: (text as NSString).length)
            pendingEdit = pendingEdit.map { NSUnionRange($0, changed) } ?? changed
            if replaced.contains("`") || text.contains("`") { pendingFull = true }
            guard text == "\n", tv.markedTextRange == nil else { return true }
            let ns = tv.text as NSString
            let lineStart = ns.lineRange(for: NSRange(location: range.location, length: 0)).location
            let before = ns.substring(with: NSRange(location: lineStart, length: range.location - lineStart))
            guard let next = listContinuation(lineBeforeCursor: before) else { return true }
            if next.endList {
                // Empty item: replace its marker ("> " inside a quote) and stay on this line.
                let lineEnd = NSMaxRange(range)
                guard let start = tv.position(from: tv.beginningOfDocument, offset: lineStart),
                      let end = tv.position(from: tv.beginningOfDocument, offset: lineEnd),
                      let r = tv.textRange(from: start, to: end) else { return true }
                tv.replace(r, withText: next.prefix)
            } else {
                guard let start = tv.position(from: tv.beginningOfDocument, offset: range.location),
                      let end = tv.position(from: start, offset: range.length),
                      let r = tv.textRange(from: start, to: end) else { return true }
                tv.replace(r, withText: "\n" + next.prefix)
            }
            tv.scrollRangeToVisible(tv.selectedRange)
            return false
        }

        /// Tick or untick the task box whose "[ ]" is at `range`.
        func toggleTask(_ range: NSRange) {
            guard let tv = textView, range.length == 3 else { return }
            let ns = tv.text as NSString
            guard NSMaxRange(range) <= ns.length else { return }
            let inner = NSRange(location: range.location + 1, length: 1)
            let checked = ns.substring(with: inner) != " "
            let selected = tv.selectedRange
            tv.textStorage.replaceCharacters(in: inner, with: checked ? " " : "x")
            pendingEdit = inner
            tv.selectedRange = selected
            // Ticking is its own inverse, so undo (and redo) just ticks again.
            tv.undoManager?.registerUndo(withTarget: self) { $0.toggleTask(range) }
            tv.undoManager?.setActionName(checked ? "Untick" : "Tick")
            UISelectionFeedbackGenerator().selectionChanged()
            textViewDidChange(tv)
        }

        /// Formatting bar above the keyboard: one compact row that scrolls sideways when it
        /// doesn't fit, with "hide keyboard" pinned on the right. (A UIToolbar with this many
        /// items overflows on iOS 26, where every item becomes its own glass capsule.)
        func makeAccessoryBar() -> UIView {
            let bar = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 46))
            bar.autoresizingMask = .flexibleWidth
            bar.backgroundColor = .secondarySystemBackground

            let hairline = UIView()
            hairline.backgroundColor = .separator
            hairline.translatesAutoresizingMaskIntoConstraints = false

            func button(_ symbol: String, _ label: String, _ action: Selector) -> UIButton {
                var config = UIButton.Configuration.plain()
                config.image = UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 16, weight: .medium))
                config.baseForegroundColor = .label
                config.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0)
                let b = UIButton(configuration: config)
                b.accessibilityLabel = label
                b.addTarget(self, action: action, for: .touchUpInside)
                b.translatesAutoresizingMaskIntoConstraints = false
                b.widthAnchor.constraint(equalToConstant: 44).isActive = true
                b.heightAnchor.constraint(equalToConstant: 44).isActive = true
                return b
            }

            let stack = UIStackView(arrangedSubviews: [
                button("number", "Heading", #selector(heading)),
                button("bold", "Bold", #selector(bold)),
                button("italic", "Italic", #selector(italic)),
                button("list.bullet", "List", #selector(bullet)),
                button("checklist", "Task", #selector(task)),
                button("chevron.left.forwardslash.chevron.right", "Code", #selector(code)),
                button("link", "Link", #selector(link)),
                button("photo", "Add image", #selector(image)),
            ])
            stack.axis = .horizontal
            stack.spacing = 2
            stack.translatesAutoresizingMaskIntoConstraints = false

            let scroll = UIScrollView()
            scroll.showsHorizontalScrollIndicator = false
            scroll.alwaysBounceHorizontal = true
            scroll.translatesAutoresizingMaskIntoConstraints = false
            scroll.addSubview(stack)

            let divider = UIView()
            divider.backgroundColor = .separator
            divider.translatesAutoresizingMaskIntoConstraints = false
            let hide = button("keyboard.chevron.compact.down", "Hide keyboard", #selector(done))
            hide.configuration?.baseForegroundColor = .dnAccent

            for v in [hairline, scroll, divider, hide] { bar.addSubview(v) }
            NSLayoutConstraint.activate([
                hairline.topAnchor.constraint(equalTo: bar.topAnchor),
                hairline.leadingAnchor.constraint(equalTo: bar.leadingAnchor),
                hairline.trailingAnchor.constraint(equalTo: bar.trailingAnchor),
                hairline.heightAnchor.constraint(equalToConstant: 1 / UIScreen.main.scale),

                scroll.leadingAnchor.constraint(equalTo: bar.leadingAnchor, constant: 6),
                scroll.topAnchor.constraint(equalTo: bar.topAnchor, constant: 1),
                scroll.heightAnchor.constraint(equalToConstant: 44),
                scroll.trailingAnchor.constraint(equalTo: divider.leadingAnchor, constant: -4),
                stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
                stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
                stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
                stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
                stack.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),

                divider.widthAnchor.constraint(equalToConstant: 1 / UIScreen.main.scale),
                divider.heightAnchor.constraint(equalToConstant: 24),
                divider.centerYAnchor.constraint(equalTo: scroll.centerYAnchor),
                divider.trailingAnchor.constraint(equalTo: hide.leadingAnchor, constant: -4),

                hide.trailingAnchor.constraint(equalTo: bar.trailingAnchor, constant: -6),
                hide.centerYAnchor.constraint(equalTo: scroll.centerYAnchor),
            ])
            return bar
        }

        private func wrap(_ marker: String) {
            guard let tv = textView, let range = tv.selectedTextRange else { return }
            let selected = tv.text(in: range) ?? ""
            tv.replace(range, withText: marker + selected + marker)
            if selected.isEmpty, let pos = tv.position(from: range.start, offset: marker.count) {
                tv.selectedTextRange = tv.textRange(from: pos, to: pos)
            }
        }

        private func prefixLine(_ prefix: String) {
            guard let tv = textView else { return }
            let ns = tv.text as NSString
            let lineRange = ns.lineRange(for: NSRange(location: tv.selectedRange.location, length: 0))
            let line = ns.substring(with: lineRange)
            let cursor = tv.selectedRange.location
            if line.hasPrefix(prefix) {
                tv.textStorage.replaceCharacters(in: NSRange(location: lineRange.location, length: (prefix as NSString).length), with: "")
                tv.selectedRange = NSRange(location: max(lineRange.location, cursor - (prefix as NSString).length), length: 0)
            } else {
                tv.textStorage.replaceCharacters(in: NSRange(location: lineRange.location, length: 0), with: prefix)
                tv.selectedRange = NSRange(location: cursor + (prefix as NSString).length, length: 0)
            }
            textViewDidChange(tv)
        }

        @objc func heading() { prefixLine("# ") }
        @objc func bold() { wrap("**") }
        @objc func italic() { wrap("_") }
        @objc func bullet() { prefixLine("- ") }
        @objc func task() { prefixLine("- [ ] ") }
        @objc func code() { wrap("`") }
        @objc func link() {
            guard let tv = textView, let range = tv.selectedTextRange else { return }
            let selected = tv.text(in: range) ?? ""
            tv.replace(range, withText: "[\(selected)](https://)")
        }
        @objc func done() { textView?.resignFirstResponder() }
        @objc func image() { parent.onPickImage() }
    }
}


extension NSAttributedString.Key {
    /// Characters not drawn at all (Markdown syntax on lines that aren't being edited).
    static let dnHidden = NSAttributedString.Key("dnHidden")
    /// A task box "[ ]" / "[x]" (value: Bool, checked); drawn as a checkbox.
    static let dnTask = NSAttributedString.Key("dnTask")
    /// A list bullet; drawn as a dot.
    static let dnBullet = NSAttributedString.Key("dnBullet")
    /// A quoted paragraph; drawn with a bar on the left.
    static let dnQuote = NSAttributedString.Key("dnQuote")
    /// A thematic break ("---"); drawn as a rule.
    static let dnRule = NSAttributedString.Key("dnRule")
}

/// UITextView on a TextKit 1 stack, so the layout manager can hide syntax and draw widgets.
final class MarkdownUITextView: UITextView, UIGestureRecognizerDelegate {
    var onToggleTask: ((NSRange) -> Void)?
    /// The current touch started on a task box: it ticks the box and must not start editing.
    private(set) var touchOnTask = false

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        // UIKit also hit-tests mid-touch with other (or no) events: leave the flag alone then.
        guard hit != nil, event?.type == .touches else { return hit }
        touchOnTask = taskBox(at: point) != nil
        // Keep the touch on this view so the text-selection gestures of subviews don't see it.
        return touchOnTask ? self : hit
    }

    // The flag only matters for the touch that set it. Clear it once that touch has been fully
    // handled (UITextView may try to begin editing while the touch ends), so a later
    // programmatic becomeFirstResponder() isn't refused.
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesEnded(touches, with: event)
        clearTouchOnTaskSoon()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesCancelled(touches, with: event)
        clearTouchOnTaskSoon()
    }

    private func clearTouchOnTaskSoon() {
        DispatchQueue.main.async { [weak self] in self?.touchOnTask = false }
    }
    private lazy var taskTap: UITapGestureRecognizer = {
        let g = UITapGestureRecognizer(target: self, action: #selector(tappedTask(_:)))
        g.delegate = self
        return g
    }()

    static func make() -> MarkdownUITextView {
        let storage = NSTextStorage()
        let layout = MarkdownLayoutManager()
        layout.delegate = layout
        storage.addLayoutManager(layout)
        let container = NSTextContainer(size: CGSize(width: 0, height: CGFloat.greatestFiniteMagnitude))
        container.widthTracksTextView = true
        layout.addTextContainer(container)
        let tv = MarkdownUITextView(frame: .zero, textContainer: container)
        tv.addGestureRecognizer(tv.taskTap)
        return tv
    }

    /// The "[ ]" range of the task box under `point` (in view coordinates), if any.
    func taskBox(at point: CGPoint) -> NSRange? {
        let p = CGPoint(x: point.x - textContainerInset.left, y: point.y - textContainerInset.top)
        let glyph = layoutManager.glyphIndex(for: p, in: textContainer)
        let char = layoutManager.characterIndexForGlyph(at: glyph)
        for i in [char, char - 1, char + 1, char - 2, char + 2] where i >= 0 && i < textStorage.length {
            var r = NSRange()
            let line = (text as NSString).lineRange(for: NSRange(location: i, length: 0))
            guard textStorage.attribute(.dnTask, at: i, longestEffectiveRange: &r, in: line) != nil else { continue }
            let g = layoutManager.glyphRange(forCharacterRange: r, actualCharacterRange: nil)
            let box = layoutManager.boundingRect(forGlyphRange: g, in: textContainer).insetBy(dx: -10, dy: -4)
            if box.contains(p) { return r }
        }
        return nil
    }

    @objc private func tappedTask(_ g: UITapGestureRecognizer) {
        if let r = taskBox(at: g.location(in: self)) { onToggleTask?(r) }
        clearTouchOnTaskSoon()
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        g === taskTap
    }

    override func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        let onBox = taskBox(at: g.location(in: self)) != nil
        if g === taskTap { return onBox }
        // A tap on a task box ticks it; it doesn't also move the cursor or raise the keyboard.
        if onBox && (g is UITapGestureRecognizer || g is UILongPressGestureRecognizer) { return false }
        return super.gestureRecognizerShouldBegin(g)
    }
}

/// Hides `.dnHidden` characters and draws bullets, task boxes, quote bars and rules.
final class MarkdownLayoutManager: NSLayoutManager, NSLayoutManagerDelegate {
    func layoutManager(
        _ lm: NSLayoutManager,
        shouldGenerateGlyphs glyphs: UnsafePointer<CGGlyph>,
        properties props: UnsafePointer<NSLayoutManager.GlyphProperty>,
        characterIndexes: UnsafePointer<Int>,
        font: UIFont,
        forGlyphRange glyphRange: NSRange
    ) -> Int {
        guard let storage = lm.textStorage else { return 0 }
        var hidden = false
        var out = [NSLayoutManager.GlyphProperty](UnsafeBufferPointer(start: props, count: glyphRange.length))
        for i in 0..<glyphRange.length {
            let c = characterIndexes[i]
            if c < storage.length, storage.attribute(.dnHidden, at: c, effectiveRange: nil) != nil {
                out[i] = .null
                hidden = true
            }
        }
        guard hidden else { return 0 }
        out.withUnsafeBufferPointer {
            lm.setGlyphs(glyphs, properties: $0.baseAddress!, characterIndexes: characterIndexes, font: font, forGlyphRange: glyphRange)
        }
        return glyphRange.length
    }

    override func drawBackground(forGlyphRange glyphsToShow: NSRange, at origin: CGPoint) {
        super.drawBackground(forGlyphRange: glyphsToShow, at: origin)
        guard let storage = textStorage, let container = textContainers.first else { return }
        let chars = characterRange(forGlyphRange: glyphsToShow, actualGlyphRange: nil)
        storage.enumerateAttribute(.dnQuote, in: chars) { value, range, _ in
            guard value != nil else { return }
            // Start at the first visible character: hidden glyphs at the start of a line ("> ")
            // can be laid out at the end of the previous line, which would stretch the bar up.
            var start = range.location
            while start < NSMaxRange(range) - 1, storage.attribute(.dnHidden, at: start, effectiveRange: nil) != nil { start += 1 }
            let visible = NSRange(location: start, length: NSMaxRange(range) - start)
            let g = glyphRange(forCharacterRange: visible, actualCharacterRange: nil)
            var top = CGFloat.greatestFiniteMagnitude, bottom: CGFloat = 0
            enumerateLineFragments(forGlyphRange: g) { rect, _, _, _, _ in
                top = min(top, rect.minY)
                bottom = max(bottom, rect.maxY)
            }
            guard bottom > top else { return }
            UIColor.dnAccent.setFill()
            UIBezierPath(roundedRect: CGRect(x: origin.x + 1, y: origin.y + top + 2, width: 3, height: bottom - top - 4), cornerRadius: 1.5).fill()
        }
        storage.enumerateAttribute(.dnRule, in: chars) { value, range, _ in
            guard value != nil else { return }
            let g = glyphRange(forCharacterRange: range, actualCharacterRange: nil)
            let line = lineFragmentRect(forGlyphAt: g.location, effectiveRange: nil)
            UIColor.dnBorder.setFill()
            UIRectFill(CGRect(x: origin.x + line.minX, y: origin.y + line.midY, width: container.size.width - container.lineFragmentPadding * 2, height: 1))
        }
    }

    override func drawGlyphs(forGlyphRange glyphsToShow: NSRange, at origin: CGPoint) {
        super.drawGlyphs(forGlyphRange: glyphsToShow, at: origin)
        guard let storage = textStorage, let container = textContainers.first else { return }
        let chars = characterRange(forGlyphRange: glyphsToShow, actualGlyphRange: nil)

        /// Rect of the characters in `range` and the baseline-relative metrics of their font.
        func place(_ range: NSRange) -> (rect: CGRect, baseline: CGFloat, font: UIFont) {
            let g = glyphRange(forCharacterRange: range, actualCharacterRange: nil)
            let line = lineFragmentRect(forGlyphAt: g.location, effectiveRange: nil)
            let loc = location(forGlyphAt: g.location)
            let rect = boundingRect(forGlyphRange: g, in: container)
            let font = storage.attribute(.font, at: range.location, effectiveRange: nil) as? UIFont ?? MarkdownHighlighter.bodyFont
            return (rect.offsetBy(dx: origin.x, dy: origin.y), origin.y + line.minY + loc.y, font)
        }

        storage.enumerateAttribute(.dnBullet, in: chars) { value, range, _ in
            guard value != nil else { return }
            let (rect, baseline, font) = place(range)
            let d = max(5, font.pointSize * 0.33)
            UIColor.dnFaint.setFill()
            UIBezierPath(ovalIn: CGRect(x: rect.midX - d / 2, y: baseline - font.xHeight / 2 - d / 2, width: d, height: d)).fill()
        }
        storage.enumerateAttribute(.dnTask, in: chars) { value, range, _ in
            guard let checked = value as? Bool else { return }
            let (rect, baseline, font) = place(range)
            let side = MarkdownHighlighter.taskBoxSide
            let box = CGRect(x: rect.minX + 1, y: (baseline - font.capHeight / 2 - side / 2).rounded(), width: side, height: side)
            let path = UIBezierPath(roundedRect: box.insetBy(dx: 0.75, dy: 0.75), cornerRadius: side * 0.28)
            if checked {
                UIColor.dnAccent.setFill()
                path.fill()
                let tick = UIBezierPath()
                tick.move(to: CGPoint(x: box.minX + side * 0.27, y: box.minY + side * 0.52))
                tick.addLine(to: CGPoint(x: box.minX + side * 0.44, y: box.minY + side * 0.69))
                tick.addLine(to: CGPoint(x: box.minX + side * 0.74, y: box.minY + side * 0.33))
                tick.lineWidth = side * 0.12
                tick.lineCapStyle = .round
                tick.lineJoinStyle = .round
                UIColor.white.setStroke()
                tick.stroke()
            } else {
                path.lineWidth = 1.5
                UIColor.dnFaint.setStroke()
                path.stroke()
            }
        }
    }
}

/// Styles a note for the live-preview editor. Fast enough for note-sized documents.
///
/// Syntax is hidden (`.dnHidden`) everywhere except in `active`, the paragraph being edited;
/// list bullets and task boxes are always drawn as widgets unless the cursor is on the marker.
enum MarkdownHighlighter {
    static let bodyFont = UIFont.preferredFont(forTextStyle: .body)
    static let monoFont = UIFont.monospacedSystemFont(ofSize: bodyFont.pointSize * 0.9, weight: .regular)
    static let taskBoxSide = (bodyFont.capHeight * 1.45).rounded()

    static var baseAttributes: [NSAttributedString.Key: Any] {
        [.font: bodyFont, .foregroundColor: UIColor.dnText, .paragraphStyle: paragraph()]
    }

    private static func paragraph(indent: CGFloat = 0, firstLine: CGFloat = 0) -> NSParagraphStyle {
        let p = NSMutableParagraphStyle()
        p.lineSpacing = 4
        p.paragraphSpacing = 2
        p.headIndent = indent
        p.firstLineHeadIndent = firstLine
        return p
    }

    private static func rx(_ pattern: String, _ opts: NSRegularExpression.Options = []) -> NSRegularExpression {
        try! NSRegularExpression(pattern: pattern, options: opts)
    }

    // Block syntax, matched against one line.
    private static let heading = rx(#"^(#{1,6})(?:[ \t]+|$)"#)
    private static let rule = rx(#"^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$"#)
    private static let task = rx(#"^([ \t]*)([-*+] )(\[[ xX]\])(?: |$)"#)
    private static let bullet = rx(#"^([ \t]*)([-*+]) "#)
    private static let ordered = rx(#"^([ \t]*)(\d{1,9}[.)]) "#)
    private static let quote = rx(#"^>[ \t]?"#)
    private static let marker = rx(#"^[ \t]*(?:[-*+] (?:\[[ xX]\] ?)?|\d{1,9}[.)] |> ?)"#)

    /// Length of a line's list/task/quote marker ("  - [ ] " → 8), 0 when there is none.
    static func markerLength(_ line: String) -> Int {
        marker.firstMatch(in: line, range: NSRange(location: 0, length: (line as NSString).length))?.range.length ?? 0
    }
    // Inline syntax, matched against the whole note.
    private static let bold = rx(#"(\*\*|__)(?=\S)(.+?)(?<=\S)\1"#)
    private static let italic = rx(#"(?<![\*_\w])([\*_])(?=\S)([^\*_\n]+?)(?<=\S)\1(?![\*_\w])"#)
    private static let strike = rx(#"~~(?=\S)(.+?)(?<=\S)~~"#)
    private static let inlineCode = rx(#"`([^`\n]+)`"#)
    private static let link = rx(#"(?<!!)\[([^\]\n]+)\]\(([^)\n]+)\)"#)
    private static let image = rx(#"!\[[^\]\n]*\]\([^)\n]+\)"#)

    static func highlight(_ text: String) -> NSAttributedString {
        let s = NSMutableAttributedString(string: text, attributes: baseAttributes)
        apply(to: s, active: nil, cursor: nil)
        return s
    }

    /// Whether the line starting at `location` is inside a fenced code block: an odd number of
    /// fence lines ("```") before it.
    static func inFence(_ ns: NSString, before location: Int) -> Bool {
        var open = false
        var search = NSRange(location: 0, length: location)
        while true {
            let r = ns.range(of: "```", options: [], range: search)
            guard r.location != NSNotFound else { break }
            let line = ns.lineRange(for: NSRange(location: r.location, length: 0))
            let lead = ns.substring(with: NSRange(location: line.location, length: r.location - line.location))
            if lead.allSatisfy({ $0 == " " || $0 == "\t" }) { open.toggle() }
            let next = NSMaxRange(line)
            guard next < location else { break }
            search = NSRange(location: next, length: location - next)
        }
        return open
    }

    /// Styles `region` (whole lines; the whole note when nil). Every rule is line-local except
    /// fenced code, whose state is worked out from the text before the region.
    static func apply(to s: NSMutableAttributedString, active: NSRange?, cursor: Int?, region: NSRange? = nil) {
        let ns = s.string as NSString
        let full = region.map { r -> NSRange in
            let loc = min(r.location, ns.length)
            return ns.paragraphRange(for: NSRange(location: loc, length: min(r.length, ns.length - loc)))
        } ?? NSRange(location: 0, length: s.length)
        func isActive(_ loc: Int) -> Bool {
            guard let a = active else { return false }
            return a.length == 0 ? loc == a.location : NSLocationInRange(loc, a)
        }
        func hide(_ r: NSRange) { if r.length > 0 { s.addAttribute(.dnHidden, value: true, range: r) } }
        func width(_ str: String, _ font: UIFont = bodyFont) -> CGFloat { (str as NSString).size(withAttributes: [.font: font]).width }

        s.beginEditing()
        s.setAttributes(baseAttributes, range: full)
        var code: [NSRange] = []

        // Blocks, line by line.
        var inFence = full.location > 0 && Self.inFence(ns, before: full.location)
        var pos = full.location
        while pos < NSMaxRange(full) {
            let lineRange = ns.lineRange(for: NSRange(location: pos, length: 0))
            pos = NSMaxRange(lineRange)
            var contentLength = lineRange.length
            while contentLength > 0, [10, 13].contains(ns.character(at: lineRange.location + contentLength - 1)) { contentLength -= 1 }
            let content = NSRange(location: lineRange.location, length: contentLength)
            let line = ns.substring(with: content)
            let local = NSRange(location: 0, length: (line as NSString).length)
            func abs(_ r: NSRange) -> NSRange { NSRange(location: r.location + content.location, length: r.length) }
            let lineActive = isActive(content.location)

            if line.trimmingCharacters(in: .whitespaces).hasPrefix("```") || inFence {
                let fenceLine = line.trimmingCharacters(in: .whitespaces).hasPrefix("```")
                s.addAttributes([.font: monoFont, .backgroundColor: UIColor.dnChrome, .foregroundColor: fenceLine ? UIColor.dnFaint : UIColor.dnText], range: lineRange)
                code.append(lineRange)
                if fenceLine { inFence.toggle() }
                continue
            }
            if let m = heading.firstMatch(in: line, range: local) {
                let level = m.range(at: 1).length
                let scale: CGFloat = [1.6, 1.32, 1.14, 1.05, 1.0, 1.0][level - 1]
                let size = bodyFont.pointSize * scale
                s.addAttributes([.font: UIFont.systemFont(ofSize: size, weight: level <= 2 ? .bold : .semibold), .kern: -0.022 * size], range: content)
                s.addAttribute(.foregroundColor, value: UIColor.dnFaint, range: abs(m.range))
                if !lineActive { hide(abs(m.range)) }
                continue
            }
            if rule.firstMatch(in: line, range: local) != nil {
                if !lineActive {
                    s.addAttributes([.foregroundColor: UIColor.clear, .dnRule: true], range: content)
                } else {
                    s.addAttribute(.foregroundColor, value: UIColor.dnFaint, range: content)
                }
                continue
            }
            if let m = quote.firstMatch(in: line, range: local) {
                s.addAttributes([.dnQuote: true, .foregroundColor: UIColor.dnMuted, .paragraphStyle: paragraph(indent: 14, firstLine: 14)], range: lineRange)
                if lineActive { s.addAttribute(.foregroundColor, value: UIColor.dnAccent, range: abs(m.range)) } else { hide(abs(m.range)) }
                continue
            }
            if let m = task.firstMatch(in: line, range: local) {
                let indent = (line as NSString).substring(with: m.range(at: 1))
                let marker = abs(m.range(at: 2)), box = abs(m.range(at: 3))
                let checked = ns.substring(with: box).lowercased() == "[x]"
                let textStart = NSMaxRange(box) + 1
                // Raw while the cursor is on the marker itself, so it can still be edited.
                if lineActive, let c = cursor, c < textStart, c >= content.location {
                    s.addAttribute(.foregroundColor, value: UIColor.dnAccent, range: NSRange(location: marker.location, length: NSMaxRange(box) - marker.location))
                } else {
                    // Not .dnHidden: TextKit treats a line starting with hidden glyphs as wrapped
                    // and applies the hanging indent to it. A near-zero-width font does the job.
                    s.addAttributes([.foregroundColor: UIColor.clear, .font: UIFont.systemFont(ofSize: 0.01)], range: marker)
                    s.addAttributes([.foregroundColor: UIColor.clear, .dnTask: checked], range: box)
                    // Room for the drawn box plus a gap before the text.
                    let extra = taskBoxSide + 4 - width(ns.substring(with: box))
                    s.addAttribute(.kern, value: max(0, extra), range: NSRange(location: NSMaxRange(box) - 1, length: 1))
                }
                let hanging = width(indent) + max(width("[ ]"), taskBoxSide + 4) + width(" ")
                s.addAttribute(.paragraphStyle, value: paragraph(indent: hanging), range: lineRange)
                if checked, textStart < NSMaxRange(content) {
                    let text = NSRange(location: textStart, length: NSMaxRange(content) - textStart)
                    s.addAttributes([.foregroundColor: UIColor.dnFaint, .strikethroughStyle: NSUnderlineStyle.single.rawValue, .strikethroughColor: UIColor.dnFaint], range: text)
                }
                continue
            }
            if let m = bullet.firstMatch(in: line, range: local) {
                let indent = (line as NSString).substring(with: m.range(at: 1))
                let marker = abs(m.range(at: 2))
                if lineActive, let c = cursor, c <= marker.location, c >= content.location {
                    s.addAttribute(.foregroundColor, value: UIColor.dnAccent, range: marker)
                } else {
                    s.addAttributes([.foregroundColor: UIColor.clear, .dnBullet: true], range: marker)
                }
                s.addAttribute(.paragraphStyle, value: paragraph(indent: width(indent) + width("- ")), range: lineRange)
                continue
            }
            if let m = ordered.firstMatch(in: line, range: local) {
                let indent = (line as NSString).substring(with: m.range(at: 1))
                let marker = (line as NSString).substring(with: m.range(at: 2))
                s.addAttribute(.foregroundColor, value: UIColor.dnAccent, range: abs(m.range(at: 2)))
                s.addAttribute(.paragraphStyle, value: paragraph(indent: width(indent) + width(marker + " ")), range: lineRange)
            }
        }

        // Inline syntax, outside code blocks.
        func each(_ r: NSRegularExpression, _ body: (NSTextCheckingResult) -> Void) {
            r.enumerateMatches(in: s.string, range: full) { m, _, _ in
                guard let m, !code.contains(where: { NSIntersectionRange($0, m.range).length > 0 }) else { return }
                body(m)
            }
        }
        func font(at loc: Int) -> UIFont { (s.attribute(.font, at: loc, effectiveRange: nil) as? UIFont) ?? bodyFont }
        func hideMarks(_ m: NSTextCheckingResult, _ length: Int) {
            guard !isActive(m.range.location) else {
                s.addAttribute(.foregroundColor, value: UIColor.dnFaint, range: NSRange(location: m.range.location, length: length))
                s.addAttribute(.foregroundColor, value: UIColor.dnFaint, range: NSRange(location: NSMaxRange(m.range) - length, length: length))
                return
            }
            hide(NSRange(location: m.range.location, length: length))
            hide(NSRange(location: NSMaxRange(m.range) - length, length: length))
        }
        each(inlineCode) { m in
            s.addAttributes([.font: monoFont, .backgroundColor: UIColor.dnHover], range: m.range)
            code.append(m.range)
            hideMarks(m, 1)
        }
        each(bold) { m in
            s.addAttribute(.font, value: UIFont.systemFont(ofSize: font(at: m.range.location).pointSize, weight: .bold), range: m.range)
            hideMarks(m, 2)
        }
        each(italic) { m in
            let f = font(at: m.range.location)
            if let d = f.fontDescriptor.withSymbolicTraits(f.fontDescriptor.symbolicTraits.union(.traitItalic)) {
                s.addAttribute(.font, value: UIFont(descriptor: d, size: f.pointSize), range: m.range)
            }
            hideMarks(m, 1)
        }
        each(strike) { m in
            s.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: m.range)
            hideMarks(m, 2)
        }
        each(link) { m in
            s.addAttribute(.foregroundColor, value: UIColor.dnAccent, range: m.range(at: 1))
            let open = NSRange(location: m.range.location, length: 1)
            let tail = NSRange(location: NSMaxRange(m.range(at: 1)), length: NSMaxRange(m.range) - NSMaxRange(m.range(at: 1)))
            if isActive(m.range.location) {
                s.addAttribute(.foregroundColor, value: UIColor.dnFaint, range: open)
                s.addAttribute(.foregroundColor, value: UIColor.dnFaint, range: tail)
            } else {
                hide(open)
                hide(tail)
            }
        }
        each(image) { m in
            s.addAttribute(.foregroundColor, value: UIColor.dnFaint, range: m.range)
            s.addAttribute(.font, value: UIFont.systemFont(ofSize: bodyFont.pointSize * 0.85), range: m.range)
            s.addAttribute(.foregroundColor, value: UIColor.dnAccent, range: NSRange(location: m.range.location, length: 1))
        }
        s.endEditing()
    }
}
