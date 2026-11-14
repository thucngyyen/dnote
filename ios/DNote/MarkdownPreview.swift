import SwiftUI
import WebKit

/// Read mode: HTML rendered by the shared Rust core (same output as web and Android).
///
/// The core rewrites relative image/note links to vault-root paths, so the page is written to
/// `<vault>/.dnote/preview.html` with `<base href>` set to the vault folder and loaded with read
/// access to the vault: `![](../Attachments/a.jpg)` then shows the image straight from disk.
struct MarkdownPreview: UIViewRepresentable {
    let markdown: String
    let notePath: String
    let root: URL
    let openPath: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = false
        let web = WKWebView(frame: .zero, configuration: config)
        web.isOpaque = false
        web.backgroundColor = .dnBg
        web.scrollView.backgroundColor = .dnBg
        web.navigationDelegate = context.coordinator
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        context.coordinator.parent = self
        let html = Self.page(renderMarkdown(markdown: markdown, notePath: notePath), base: root)
        guard context.coordinator.lastHTML != html else { return }
        context.coordinator.lastHTML = html
        let file = root.appendingPathComponent(".dnote/preview.html")
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try html.write(to: file, atomically: true, encoding: .utf8)
            web.loadFileURL(file, allowingReadAccessTo: root)
        } catch {
            web.loadHTMLString(html, baseURL: root)
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var parent: MarkdownPreview
        var lastHTML = ""
        init(_ parent: MarkdownPreview) { self.parent = parent }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard action.navigationType == .linkActivated, let url = action.request.url else {
                decisionHandler(.allow)
                return
            }
            decisionHandler(.cancel)
            if url.isFileURL {
                let rootPath = parent.root.standardizedFileURL.path
                let target = url.standardizedFileURL.path
                if target.hasPrefix(rootPath + "/") {
                    parent.openPath(String(target.dropFirst(rootPath.count + 1)))
                }
            } else if ["http", "https", "mailto", "tel"].contains(url.scheme ?? "") {
                UIApplication.shared.open(url)
            }
        }
    }

    static func page(_ body: String, base: URL) -> String {
        """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <base href="\(base.absoluteString.hasSuffix("/") ? base.absoluteString : base.absoluteString + "/")">
        <style>
        :root { color-scheme: light dark; --text:#0A0C10; --text2:#334155; --muted:#64748B; --faint:#94A3B8; --chrome:#F5F7FA; --border:#E2E8F0; --bg:#FFFFFF; --accent:#2563EB; }
        @media (prefers-color-scheme: dark) { :root { --text:#F5F7FA; --text2:#CBD5E1; --muted:#94A3B8; --faint:#64748B; --chrome:#141821; --border:#1E2430; --bg:#0A0C10; --accent:#3B82F6; } }
        body { font: -apple-system-body; line-height: 1.7; margin: 0; padding: 18px 20px 120px; color: var(--text2); background: var(--bg); word-wrap: break-word; }
        h1,h2,h3,h4 { color: var(--text); line-height: 1.2; font-weight: 700; letter-spacing: -0.02em; margin: 1.5em 0 .45em; }
        h1 { font-size: 1.75em; margin-top: .2em; } h2 { font-size: 1.35em; } h3 { font-size: 1.12em; }
        strong { color: var(--text); font-weight: 600; }
        a { color: var(--accent); text-decoration: none; font-weight: 500; }
        code { font: .86em ui-monospace, Menlo, monospace; background: var(--chrome); padding: .12em .38em; border-radius: 5px; color: var(--text); }
        pre { background: var(--chrome); border: 1px solid var(--border); padding: 12px 14px; border-radius: 10px; overflow-x: auto; }
        pre code { background: none; padding: 0; }
        blockquote { margin: 0; padding-left: 14px; border-left: 3px solid var(--accent); color: var(--muted); }
        img { display: block; max-width: 100%; height: auto; border-radius: 12px; margin: 12px 0; }
        table { border-collapse: collapse; display: block; overflow-x: auto; }
        th, td { border-bottom: 1px solid var(--border); padding: 7px 14px 7px 0; text-align: left; }
        th { color: var(--text); }
        li:has(> input[type=checkbox]) { list-style: none; margin-left: -1.3em; }
        input[type=checkbox] { -webkit-appearance: none; appearance: none; width: 16px; height: 16px; border: 1.5px solid var(--faint); border-radius: 5px; margin: 0 .55em 0 0; vertical-align: -2px; display: inline-block; position: relative; box-sizing: border-box; }
        input[type=checkbox]:checked { background: var(--accent); border-color: var(--accent); }
        input[type=checkbox]:checked::after { content: ""; position: absolute; left: 3.5px; top: 3.5px; width: 7px; height: 4px; border: solid #fff; border-width: 0 0 1.6px 1.6px; transform: rotate(-45deg); }
        li:has(> input:checked) { color: var(--muted); text-decoration: line-through; text-decoration-color: var(--faint); }
        hr { border: 0; border-top: 1px solid var(--border); }
        </style></head><body>\(body)</body></html>
        """
    }
}
