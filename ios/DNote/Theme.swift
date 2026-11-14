import SwiftUI
import UIKit

// MARK: - Brand tokens (docs/DESIGN.md)

private func hex(_ v: UInt32, _ a: CGFloat = 1) -> UIColor {
    UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: a)
}

private func dynamic(_ light: UIColor, _ dark: UIColor) -> UIColor {
    UIColor { $0.userInterfaceStyle == .dark ? dark : light }
}

extension UIColor {
    /// Editor / main content.
    static let dnBg = dynamic(hex(0xFFFFFF), hex(0x0A0C10))
    /// Lists, bars, chrome (Paper in light).
    static let dnChrome = dynamic(hex(0xF5F7FA), hex(0x0E1117))
    /// Cards, sheets, inputs.
    static let dnSurface = dynamic(hex(0xFFFFFF), hex(0x141821))
    static let dnText = dynamic(hex(0x0A0C10), hex(0xF5F7FA))
    static let dnText2 = dynamic(hex(0x334155), hex(0xCBD5E1))
    static let dnMuted = dynamic(hex(0x64748B), hex(0x94A3B8))
    static let dnFaint = dynamic(hex(0x94A3B8), hex(0x64748B))
    static let dnBorder = dynamic(hex(0xE2E8F0), hex(0x1E2430))
    static let dnHover = dynamic(hex(0xEEF2F7), hex(0x161B24))
    /// Blue 600 in light, Blue 500 in dark.
    static let dnAccent = dynamic(hex(0x2563EB), hex(0x3B82F6))
    static let dnAccentSoft = dynamic(hex(0x2563EB, 0.08), hex(0x3B82F6, 0.14))
    static let dnSuccess = dynamic(hex(0x16A34A), hex(0x22C55E))

    static func dnTag(_ name: String) -> UIColor {
        switch name {
        case "amber": hex(0xF59E0B)
        case "blue": hex(0x3B82F6)
        case "green": hex(0x22C55E)
        case "red": hex(0xEF4444)
        case "violet": hex(0x8B5CF6)
        case "pink": hex(0xEC4899)
        default: hex(0x94A3B8)
        }
    }
}

extension Color {
    static let dnBg = Color(uiColor: .dnBg)
    static let dnChrome = Color(uiColor: .dnChrome)
    static let dnSurface = Color(uiColor: .dnSurface)
    static let dnText = Color(uiColor: .dnText)
    static let dnText2 = Color(uiColor: .dnText2)
    static let dnMuted = Color(uiColor: .dnMuted)
    static let dnFaint = Color(uiColor: .dnFaint)
    static let dnBorder = Color(uiColor: .dnBorder)
    static let dnHover = Color(uiColor: .dnHover)
    static let dnAccent = Color(uiColor: .dnAccent)
    static let dnAccentSoft = Color(uiColor: .dnAccentSoft)
    static let dnSuccess = Color(uiColor: .dnSuccess)
    static func dnTag(_ name: String) -> Color { Color(uiColor: .dnTag(name)) }
}

enum AppTheme: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

// MARK: - Components

/// Primary action: blue, 12 pt corners.
struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 16, weight: .semibold))
            .frame(maxWidth: .infinity, minHeight: 50)
            .foregroundStyle(.white)
            .background(Color.dnAccent.opacity(configuration.isPressed ? 0.85 : 1), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(color: Color.dnAccent.opacity(0.25), radius: 10, y: 4)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// Brand mark + wordmark with the blue "D".
struct Wordmark: View {
    var size: CGFloat = 22

    var body: some View {
        HStack(spacing: size * 0.38) {
            Image("Mark")
                .resizable()
                .scaledToFit()
                .frame(height: size * 1.12)
            (Text("D").foregroundColor(.dnAccent) + Text("Note").foregroundColor(.dnText))
                .font(.system(size: size, weight: .bold))
                .tracking(-size * 0.03)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("DNote")
    }
}

/// Small coloured dot, or a star for Favorite.
struct TagDot: View {
    let tag: TagInfo
    var size: CGFloat = 7

    var body: some View {
        if tag.id == "favorite" {
            Image(systemName: "star.fill")
                .font(.system(size: size + 2, weight: .bold))
                .foregroundStyle(Color.dnTag(tag.color))
        } else {
            Circle().fill(Color.dnTag(tag.color)).frame(width: size, height: size)
        }
    }
}

/// "10:42", "Yesterday", "Mon", "12 Oct".
func shortDate(_ ms: Int64) -> String {
    guard ms > 0 else { return "" }
    let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    let cal = Calendar.current
    if cal.isDateInToday(date) { return date.formatted(date: .omitted, time: .shortened) }
    if cal.isDateInYesterday(date) { return "Yesterday" }
    if let days = cal.dateComponents([.day], from: date, to: Date()).day, days < 7 {
        return date.formatted(.dateTime.weekday(.abbreviated))
    }
    if cal.isDate(date, equalTo: Date(), toGranularity: .year) {
        return date.formatted(.dateTime.day().month(.abbreviated))
    }
    return date.formatted(date: .numeric, time: .omitted)
}

// MARK: - Zoom transition (iOS 18+, no-op before)

private struct TransitionNamespaceKey: EnvironmentKey {
    static let defaultValue: Namespace.ID? = nil
}

extension EnvironmentValues {
    var zoomNamespace: Namespace.ID? {
        get { self[TransitionNamespaceKey.self] }
        set { self[TransitionNamespaceKey.self] = newValue }
    }
}

struct ZoomSource: ViewModifier {
    let id: String
    @Environment(\.zoomNamespace) private var ns

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *), let ns {
            content.matchedTransitionSource(id: id, in: ns) { $0.clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous)) }
        } else {
            content
        }
    }
}

struct ZoomDestination: ViewModifier {
    let id: String
    @Environment(\.zoomNamespace) private var ns

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *), let ns {
            content.navigationTransition(.zoom(sourceID: id, in: ns))
        } else {
            content
        }
    }
}

extension View {
    func zoomSource(_ id: String) -> some View { modifier(ZoomSource(id: id)) }
    func zoomDestination(_ id: String) -> some View { modifier(ZoomDestination(id: id)) }
}

extension TagInfo: Identifiable {}
