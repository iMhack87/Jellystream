import SwiftUI
import Libmpv

/// Minimal libmpv player: renders into a CAMetalLayer via gpu-next/Vulkan
/// (MoltenVK, shipped by MPVKit). mpv+FFmpeg is what gives Jellystream
/// Direct Play of virtually every format on Apple platforms.
struct MPVPlayerView: UIViewRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MetalHostView {
        let view = MetalHostView()
        view.backgroundColor = .black
        context.coordinator.attach(to: view.metalLayer, url: url)
        return view
    }

    func updateUIView(_ uiView: MetalHostView, context: Context) {}

    static func dismantleUIView(_ uiView: MetalHostView, coordinator: Coordinator) {
        coordinator.shutdown()
    }

    final class MetalHostView: UIView {
        override class var layerClass: AnyClass { CAMetalLayer.self }
        var metalLayer: CAMetalLayer { layer as! CAMetalLayer }
    }

    final class Coordinator {
        private var mpv: OpaquePointer?

        func attach(to layer: CAMetalLayer, url: URL) {
            guard mpv == nil, let handle = mpv_create() else { return }
            mpv = handle

            var wid = Int64(Int(bitPattern: Unmanaged.passUnretained(layer).toOpaque()))
            mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &wid)
            mpv_set_option_string(handle, "vo", "gpu-next")
            mpv_set_option_string(handle, "gpu-api", "vulkan")
            mpv_set_option_string(handle, "hwdec", "videotoolbox")
            mpv_set_option_string(handle, "keep-open", "yes")

            guard mpv_initialize(handle) >= 0 else {
                shutdown()
                return
            }

            command("loadfile", url.absoluteString)
        }

        func shutdown() {
            guard let handle = mpv else { return }
            mpv = nil
            mpv_terminate_destroy(handle)
        }

        private func command(_ args: String...) {
            guard let handle = mpv else { return }
            var cStrings = args.map { UnsafePointer<CChar>(strdup($0)) }
            cStrings.append(nil)
            defer { cStrings.forEach { free(UnsafeMutablePointer(mutating: $0)) } }
            mpv_command(handle, &cStrings)
        }
    }
}

struct PlayerScreen: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topLeading) {
            MPVPlayerView(url: url)
                .ignoresSafeArea()

            #if !os(tvOS)
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.largeTitle)
                    .foregroundStyle(.white.opacity(0.7))
            }
            .padding()
            #endif
        }
        #if os(tvOS)
        .onExitCommand { dismiss() }
        #endif
    }
}
