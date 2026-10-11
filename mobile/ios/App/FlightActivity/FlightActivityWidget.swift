import ActivityKit
import SwiftUI
import WidgetKit

private func journeyURL(_ key: String) -> URL? {
    var url = URLComponents(string: "onestepflight://journey")!
    url.queryItems = [URLQueryItem(name: "journeyKey", value: key)]
    return url.url
}

struct FlightActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: FlightActivityAttributes.self) { context in
            FlightLockScreen(state: context.state, stale: context.isStale)
                .activityBackgroundTint(Color(uiColor: .secondarySystemBackground))
                .activitySystemActionForegroundColor(.primary)
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
        .foregroundStyle(.primary)
    }
}

private struct FlightDetails: View {
    let state: FlightActivityAttributes.ContentState
    let stale: Bool
    @Environment(\.dynamicTypeSize) private var typeSize
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
    }
    private func metric(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if !typeSize.isAccessibilitySize { Text(label).font(.caption).foregroundStyle(.secondary) }
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
