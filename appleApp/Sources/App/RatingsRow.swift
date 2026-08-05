import SwiftUI
import Shared

/// Audience score, tomatometer and age certificate, in that order.
///
/// Deliberately not the Rotten Tomatoes marks: the percentage carries the
/// verdict on its own, colored at the 60% line, and shipping their artwork
/// is not ours to do. Renders nothing at all when the server has no rating,
/// rather than leaving an empty strip on the page.
struct RatingsRow: View {
    let ratings: ItemRatings

    var body: some View {
        if !ratings.isEmpty {
            HStack(spacing: 14) {
                if let score = ratings.communityLabel {
                    Label {
                        Text(score).foregroundStyle(.white)
                    } icon: {
                        Image(systemName: "star.fill").foregroundStyle(Self.star)
                    }
                    .labelStyle(.titleAndIcon)
                    .accessibilityLabel("Audience rating \(score) out of 10")
                }

                if let percent = ratings.criticLabel {
                    Text(percent)
                        .foregroundStyle(
                            ratings.criticIsFresh?.boolValue == true ? Self.fresh : Self.rotten
                        )
                        .accessibilityLabel("Critic rating \(percent)")
                }

                if let certificate = ratings.officialLabel {
                    Text(certificate)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .overlay(
                            RoundedRectangle(cornerRadius: 4)
                                .stroke(.secondary.opacity(0.6), lineWidth: 1)
                        )
                }
            }
            .font(.subheadline)
        }
    }

    private static let star = Color(red: 0.96, green: 0.77, blue: 0.09)
    private static let fresh = Color(red: 0.33, green: 0.82, blue: 0.42)
    private static let rotten = Color(red: 1.0, green: 0.54, blue: 0.30)
}
