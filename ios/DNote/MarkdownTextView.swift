import SwiftUI
import UIKit

/// Native TextKit editor with live Markdown syntax highlighting and a formatting bar.
struct MarkdownTextView: UIViewRepresentable {
    let session: EditorSession
    let onEdit: () -> Void
    var onPickImage: () -> Void = {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.delegate = context.coordinator
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

        init(_ parent: MarkdownTextView) { self.parent = parent }

        func textViewDidChange(_ tv: UITextView) {
            let selected = tv.selectedRange
            MarkdownHighlighter.apply(to: tv.textStorage)
            tv.selectedRange = selected
            tv.typingAttributes = MarkdownHighlighter.baseAttributes
            parent.session.text = tv.text
            parent.onEdit()
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

/// Regex-based Markdown highlighting. Fast enough for note-sized documents and
/// keeps the raw Markdown visible (this is a plain-text editor, not WYSIWYG).
enum MarkdownHighlighter {
    static let bodyFont = UIFont.preferredFont(forTextStyle: .body)
    static let monoFont = UIFont.monospacedSystemFont(ofSize: bodyFont.pointSize * 0.92, weight: .regular)

    static var baseAttributes: [NSAttributedString.Key: Any] {
        let p = NSMutableParagraphStyle()
        p.lineSpacing = 4
        return [.font: bodyFont, .foregroundColor: UIColor.dnText, .paragraphStyle: p]
    }

    private static func rx(_ pattern: String, _ opts: NSRegularExpression.Options = [.anchorsMatchLines]) -> NSRegularExpression {
        try! NSRegularExpression(pattern: pattern, options: opts)
    }

    private static let heading = rx(#"^(#{1,6})\s.*$"#)
    private static let bold = rx(#"(\*\*|__)(?=\S)(.+?)(?<=\S)\1"#)
    private static let italic = rx(#"(?<![\*_\w])([\*_])(?=\S)([^\*_\n]+?)(?<=\S)\1(?![\*_\w])"#)
    private static let inlineCode = rx(#"`[^`\n]+`"#)
    private static let fence = rx(#"^```.*?^```[ \t]*$"#, [.anchorsMatchLines, .dotMatchesLineSeparators])
    private static let link = rx(#"\[([^\]\n]+)\]\(([^)\n]+)\)"#)
    private static let image = rx(#"!\[[^\]\n]*\]\([^)\n]+\)"#)
    private static let quote = rx(#"^>.*$"#)
    private static let listMarker = rx(#"^\s*([-*+]|\d+[.)])\s(\[[ xX]\]\s)?"#)
    private static let doneTask = rx(#"^\s*[-*+]\s\[[xX]\]\s(.*)$"#)

    static func highlight(_ text: String) -> NSAttributedString {
        let s = NSMutableAttributedString(string: text, attributes: baseAttributes)
        apply(to: s)
        return s
    }

    static func apply(to s: NSMutableAttributedString) {
        let full = NSRange(location: 0, length: s.length)
        let text = s.string
        s.beginEditing()
        s.setAttributes(baseAttributes, range: full)
        let secondary = UIColor.dnMuted
        let accent = UIColor.dnAccent

        heading.enumerateMatches(in: text, range: full) { m, _, _ in
            guard let m else { return }
            let level = m.range(at: 1).length
            let scale: CGFloat = [1.6, 1.32, 1.14, 1.05, 1.0, 1.0][level - 1]
            let size = bodyFont.pointSize * scale
            s.addAttribute(.font, value: UIFont.systemFont(ofSize: size, weight: .semibold), range: m.range)
            s.addAttribute(.kern, value: -0.025 * size, range: m.range)
            s.addAttribute(.foregroundColor, value: UIColor.dnFaint, range: m.range(at: 1))
        }
        bold.enumerateMatches(in: text, range: full) { m, _, _ in
            guard let m else { return }
            let current = (s.attribute(.font, at: m.range.location, effectiveRange: nil) as? UIFont) ?? bodyFont
            s.addAttribute(.font, value: UIFont.systemFont(ofSize: current.pointSize, weight: .bold), range: m.range)
        }
        italic.enumerateMatches(in: text, range: full) { m, _, _ in
            guard let m else { return }
            let current = (s.attribute(.font, at: m.range.location, effectiveRange: nil) as? UIFont) ?? bodyFont
            if let d = current.fontDescriptor.withSymbolicTraits(current.fontDescriptor.symbolicTraits.union(.traitItalic)) {
                s.addAttribute(.font, value: UIFont(descriptor: d, size: current.pointSize), range: m.range)
            }
        }
        link.enumerateMatches(in: text, range: full) { m, _, _ in
            guard let m else { return }
            s.addAttribute(.foregroundColor, value: accent, range: m.range(at: 1))
            s.addAttribute(.foregroundColor, value: UIColor.dnFaint, range: m.range(at: 2))
        }
        image.enumerateMatches(in: text, range: full) { m, _, _ in
            guard let m else { return }
            s.addAttribute(.foregroundColor, value: UIColor.dnAccent, range: NSRange(location: m.range.location, length: 1))
            s.addAttribute(.foregroundColor, value: UIColor.dnFaint, range: NSRange(location: m.range.location + 1, length: m.range.length - 1))
            s.addAttribute(.font, value: UIFont.systemFont(ofSize: bodyFont.pointSize * 0.85), range: m.range)
        }
        quote.enumerateMatches(in: text, range: full) { m, _, _ in
            guard let m else { return }
            s.addAttribute(.foregroundColor, value: secondary, range: m.range)
        }
        listMarker.enumerateMatches(in: text, range: full) { m, _, _ in
            guard let m else { return }
            s.addAttribute(.foregroundColor, value: UIColor.dnFaint, range: m.range)
        }
        doneTask.enumerateMatches(in: text, range: full) { m, _, _ in
            guard let m else { return }
            s.addAttribute(.foregroundColor, value: secondary, range: m.range(at: 1))
            s.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: m.range(at: 1))
        }
        let codeBg = UIColor.dnChrome
        for r in [inlineCode, fence] {
            r.enumerateMatches(in: text, range: full) { m, _, _ in
                guard let m else { return }
                s.addAttribute(.font, value: monoFont, range: m.range)
                s.addAttribute(.backgroundColor, value: codeBg, range: m.range)
                s.addAttribute(.foregroundColor, value: UIColor.dnText, range: m.range)
            }
        }
        s.endEditing()
    }
}
