import SwiftUI
import Shared

/// ATV+-style "Who's watching?" — shown at launch when several accounts are
/// known and none is active. Selecting enters the profile; the last circle
/// adds another account without touching the existing ones.
struct ProfilePickerView: View {
    @ObservedObject var model: AppModel

    #if os(tvOS)
    private let avatarSize: CGFloat = 200
    private let spacing: CGFloat = 64
    #else
    private let avatarSize: CGFloat = 110
    private let spacing: CGFloat = 32
    #endif

    var body: some View {
        VStack(spacing: spacing) {
            Text("Who's watching?")
                .font(.largeTitle.bold())
                .foregroundStyle(.white)

            HStack(alignment: .top, spacing: spacing) {
                ForEach(model.profiles, id: \.profileKey) { profile in
                    Button {
                        model.select(profile: profile)
                    } label: {
                        profileLabel(profile)
                    }
                    #if os(tvOS)
                .buttonStyle(.borderless)
                #else
                .buttonStyle(.plain)
                #endif
                }

                Button {
                    model.startAddProfile()
                } label: {
                    VStack(spacing: 12) {
                        ZStack {
                            Circle()
                                .strokeBorder(.white.opacity(0.4), lineWidth: 2)
                            Image(systemName: "plus")
                                .font(.system(size: avatarSize * 0.35, weight: .light))
                                .foregroundStyle(.white.opacity(0.7))
                        }
                        .frame(width: avatarSize, height: avatarSize)
                        Text("Add Profile")
                            .font(.headline)
                            .foregroundStyle(.secondary)
                    }
                }
                #if os(tvOS)
                .buttonStyle(.borderless)
                #else
                .buttonStyle(.plain)
                #endif
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
        .preferredColorScheme(.dark)
    }

    private func profileLabel(_ profile: PersistedSession) -> some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [Color(white: 0.25), Color(white: 0.12)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                Text(profile.initial)
                    .font(.system(size: avatarSize * 0.4, weight: .bold))
                    .foregroundStyle(.white)
            }
            .frame(width: avatarSize, height: avatarSize)

            Text(profile.displayName)
                .font(.headline)
                .foregroundStyle(.white)
            Text(profile.serverLabel)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: avatarSize * 1.4)
    }
}
