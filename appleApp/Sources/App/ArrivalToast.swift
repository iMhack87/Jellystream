import SwiftUI
import UIKit

/**
 The "<title> has arrived" notice, in its own window.

 An `.overlay` or a `ZStack` on RootView would be painted UNDERNEATH the
 player: `fullScreenCover` is a UIKit modal whose view sits above the
 presenter's whole hierarchy, so modifier order cannot save it — and
 during playback is exactly when a notice matters most. A second UIWindow
 above `.alert` is the only placement that is genuinely app-wide.

 The window is deliberately inert: never key (making it key would move
 the focus engine on tvOS), interaction off on both the window and the
 hosting view, and `hitTest` returning nil so a press always lands on the
 app underneath. It can be seen and nothing else.
 */
@MainActor
final class ArrivalToastWindow {
    static let shared = ArrivalToastWindow()

    private let model = ToastModel()
    private var window: UIWindow?
    private var pending: [String] = []
    private var runner: Task<Void, Never>?

    private init() {}

    /// Idempotent: RootView's `.onAppear` can fire more than once, and a
    /// second window would double every notice.
    func install() {
        guard window == nil else { return }
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        // At first appear the scene is usually still foregroundInactive,
        // hence the fallback rather than a strict active match
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive })
            ?? scenes.first
        else { return }

        let host = UIHostingController(rootView: ArrivalToastOverlay(model: model))
        host.view.backgroundColor = .clear
        host.view.isUserInteractionEnabled = false

        let overlay = PassthroughWindow(windowScene: scene)
        overlay.windowLevel = .alert + 1
        overlay.backgroundColor = .clear
        overlay.isUserInteractionEnabled = false
        overlay.rootViewController = host
        // Never makeKeyAndVisible: that hands over the key window, and
        // with it the tvOS focus engine
        overlay.isHidden = false
        window = overlay
    }

    /// Queued rather than replaced: two titles landing in the same poll
    /// would otherwise show as one.
    func show(message: String) {
        pending.append(message)
        guard runner == nil else { return }
        runner = Task { @MainActor in
            while !pending.isEmpty {
                model.message = pending.removeFirst()
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                model.message = nil
                try? await Task.sleep(nanoseconds: 400_000_000)
            }
            runner = nil
        }
    }
}

private final class ToastModel: ObservableObject {
    @Published var message: String?
}

/// Nothing in this window is a target — not the notice, not the empty
/// space around it. `isUserInteractionEnabled` alone would be enough on
/// iOS; this also keeps tvOS from ever considering the window for focus.
private final class PassthroughWindow: UIWindow {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }
}

/// A small notice in the top corner: away from the player's own controls,
/// and out of the way of the tvOS tab bar's focus.
private struct ArrivalToastOverlay: View {
    @ObservedObject var model: ToastModel

    #if os(tvOS)
    private let inset: CGFloat = 60
    #else
    private let inset: CGFloat = 16
    #endif

    var body: some View {
        VStack {
            HStack {
                Spacer()
                if let message = model.message {
                    Text(message)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 12)
                        .background(.black.opacity(0.85), in: Capsule())
                        .overlay(
                            Capsule().strokeBorder(.white.opacity(0.18), lineWidth: 1)
                        )
                        .shadow(radius: 12)
                        .frame(maxWidth: 320)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            Spacer()
        }
        // Below the account bar on home and below the subtitle-sync strip
        // in the player: no corner is free on every screen, and the two
        // things this must never cover are the ones people are already
        // reaching for. Same offset as the Android twin.
        .padding(.top, inset + 56)
        .padding(.horizontal, inset)
        .padding(.bottom, inset)
        .animation(.easeInOut(duration: 0.25), value: model.message)
        .allowsHitTesting(false)
    }
}
