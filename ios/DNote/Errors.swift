import Foundation

extension Error {
    /// Human-readable message for errors coming from the Rust core.
    var userMessage: String {
        if let e = self as? DNoteError {
            switch e {
            case .Io(let m), .InvalidPath(let m), .AlreadyExists(let m), .NotFound(let m), .Sync(let m):
                return m
            }
        }
        return localizedDescription
    }
}

extension String {
    /// "Projects/Plan.md" -> "Plan"
    var noteTitle: String {
        let name = (self as NSString).lastPathComponent
        for ext in [".md", ".markdown", ".txt"] where name.lowercased().hasSuffix(ext) {
            return String(name.dropLast(ext.count))
        }
        return name
    }

    var parentPath: String {
        guard let i = lastIndex(of: "/") else { return "" }
        return String(self[..<i])
    }
}
