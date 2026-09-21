import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif

enum Cinema {
    static let background = Color.black
    static let field = Color.white.opacity(0.10)
    static let stroke = Color.white.opacity(0.18)
    static let accent = Color(red: 0.90, green: 0.58, blue: 0.32)
}

/// The name Jellyfin shows in the dashboard for this install.
enum DeviceName {
    static var current: String {
        #if os(macOS)
        Host.current().localizedName ?? "Mac"
        #else
        UIDevice.current.name
        #endif
    }
}

extension View {
    /// Full-screen on a phone or TV; a sheet on a Mac, where a cover
    /// would steal the whole desktop.
    @ViewBuilder
    func playerCover<Item: Identifiable, Content: View>(
        item: Binding<Item?>,
        @ViewBuilder content: @escaping (Item) -> Content
    ) -> some View {
        #if os(macOS)
        sheet(item: item, content: content)
        #else
        fullScreenCover(item: item, content: content)
        #endif
    }

    @ViewBuilder
    func inlineNavigationTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }

    @ViewBuilder
    func darkNavigationBar() -> some View {
        #if os(iOS)
        toolbarColorScheme(.dark, for: .navigationBar)
        #else
        self
        #endif
    }

    @ViewBuilder
    func neverAutocapitalize() -> some View {
        #if os(iOS)
        textInputAutocapitalization(.never)
        #else
        self
        #endif
    }

    /// Dark cinema chrome: Form/List on macOS otherwise become the light
    /// System Settings layout with labels in a side column.
    func cinemaChrome() -> some View {
        self
            .preferredColorScheme(.dark)
            .tint(Cinema.accent)
            .background(Cinema.background)
            #if os(macOS)
            .scrollContentBackground(.hidden)
            .formStyle(.grouped)
            #endif
    }
}
