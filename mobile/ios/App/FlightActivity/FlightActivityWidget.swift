import ActivityKit
import SwiftUI
import WidgetKit

// Resolve both sides of the contrast pair from SwiftUI's Widget environment.
// UIKit dynamic colors can archive with the host app's light traits while the
// system renders white text using the Widget's dark traits.
private enum FlightPalette {
    static func background(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 0.11, green: 0.13, blue: 0.17) : Color(red: 0.96, green: 0.97, blue: 0.98)
    }
    static func ink(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 0.95, green: 0.96, blue: 0.98) : Color(red: 0.11, green: 0.15, blue: 0.22)
    }
    static func muted(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 0.72, green: 0.76, blue: 0.82) : Color(red: 0.32, green: 0.38, blue: 0.46)
    }
}

private func journeyURL(_ key: String) -> URL? {
    var url = URLComponents(string: "onestepflight://journey")!
    url.queryItems = [URLQueryItem(name: "journeyKey", value: key)]
    return url.url
}

struct FlightActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: FlightActivityAttributes.self) { context in
            FlightLockScreen(state: context.state, stale: context.isStale)
                .widgetURL(journeyURL(context.attributes.journeyKey))
        } dynamicIsland: { context in
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Label(state.flightNumber, systemImage: "airplane").font(.headline) }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(state.dateText + " · " + state.departureAirport + " → " + state.arrivalAirport).font(.caption)
                }
                DynamicIslandExpandedRegion(.bottom) { FlightDetails(state: state, stale: context.isStale) }
            } compactLeading: { Text(state.flightNumber).font(.caption) }
              compactTrailing: { Text(state.phase == "DEPARTURE" ? state.gate : state.arrivalTime).font(.caption) }
              minimal: { Image(systemName: "airplane") }
              .widgetURL(journeyURL(context.attributes.journeyKey))
        }
    }
}

private struct FlightLockScreen: View {
    let state: FlightActivityAttributes.ContentState
    let stale: Bool
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.colorScheme) private var colorScheme
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !typeSize.isAccessibilitySize { HStack(alignment: .firstTextBaseline) {
                Label(state.flightNumber, systemImage: "airplane").font(.headline)
                Spacer(minLength: 6)
                Text(state.dateText + " · " + state.departureAirport + " → " + state.arrivalAirport)
                    .font(.caption).multilineTextAlignment(.trailing)
            } } else {
                Text(state.flightNumber).font(.caption).accessibilityHidden(true)
            }
            FlightDetails(state: state, stale: stale)
        }
        .padding(10)
        .foregroundStyle(FlightPalette.ink(colorScheme))
        .activityBackgroundTint(FlightPalette.background(colorScheme))
        .activitySystemActionForegroundColor(FlightPalette.ink(colorScheme))
    }
}

private struct FlightDetails: View {
    let state: FlightActivityAttributes.ContentState
    let stale: Bool
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.colorScheme) private var colorScheme
    private var freshness: String {
        state.updatedText + (state.offline ? " · " + state.offlineLabel : stale || state.stale ? " · " + state.staleLabel : "")
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(state.nextStep).font(.headline).fixedSize(horizontal: false, vertical: true).layoutPriority(2)
                .accessibilityAddTraits(.isHeader)
                .accessibilityLabel(typeSize.isAccessibilitySize ? state.nextStep + ". " + state.flightNumber + ". " + state.dateText + ". " + state.departureAirport + " → " + state.arrivalAirport + ". " + state.timezoneText + ". " + freshness : state.nextStep)
            HStack(alignment: .top, spacing: 8) {
                metric(state.metricOneLabel, state.metricOneValue)
                metric(state.metricTwoLabel, state.metricTwoValue)
                metric(state.metricThreeLabel, state.metricThreeValue)
            }.layoutPriority(1)
            if !typeSize.isAccessibilitySize {
                HStack(alignment: .top, spacing: 6) {
                    Text(state.timezoneText)
                    Spacer(minLength: 0)
                    Text(freshness).multilineTextAlignment(.trailing)
                }.font(.caption2)
            }
        }
        .foregroundStyle(FlightPalette.ink(colorScheme))
    }
    private func metric(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if !typeSize.isAccessibilitySize { Text(label).font(.caption).foregroundStyle(FlightPalette.muted(colorScheme)) }
            Text(value.isEmpty ? state.missingLabel : value).font(typeSize.isAccessibilitySize ? .body.weight(.semibold) : .subheadline.weight(.semibold))
        }.frame(maxWidth: .infinity, alignment: .leading)
         .accessibilityElement(children: .combine)
         .accessibilityLabel(label + " " + (value.isEmpty ? state.missingLabel : value))
    }
}

@main
struct FlightActivityBundle: WidgetBundle {
    var body: some Widget { FlightActivityWidget() }
}
