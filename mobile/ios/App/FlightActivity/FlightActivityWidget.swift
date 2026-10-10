import ActivityKit
import SwiftUI
import WidgetKit

private let flightBlue = Color(red: 44 / 255, green: 100 / 255, blue: 142 / 255)
private let flightInk = Color(red: 27 / 255, green: 37 / 255, blue: 55 / 255)
private let journeyURL = URL(string: "onestepflight://journey")!

struct FlightActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: FlightActivityAttributes.self) { context in
            FlightLockScreen(context: context)
                .activityBackgroundTint(Color(red: 243 / 255, green: 244 / 255, blue: 247 / 255))
                .activitySystemActionForegroundColor(flightInk)
                .widgetURL(journeyURL)
        } dynamicIsland: { context in
            let old = context.isStale || context.state.stale || context.state.expiresAt <= Date()
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.state.flightNumber, systemImage: "airplane").font(.headline)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(old ? "—" : (context.state.gate.isEmpty ? "—" : context.state.gate)).font(.headline)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(old ? context.state.staleLabel : context.state.nextStep).font(.subheadline)
                        Text(context.state.updatedLabel + ": " + context.state.updatedText).font(.caption2)
                    }
                }
            } compactLeading: {
                Text(context.state.flightNumber).font(.caption2)
            } compactTrailing: {
                if old { Image(systemName: "clock.badge.exclamationmark") }
                else { Text(context.state.gate.isEmpty ? "—" : context.state.gate).font(.caption) }
            } minimal: {
                Image(systemName: old ? "clock.badge.exclamationmark" : "airplane")
            }
            .widgetURL(journeyURL)
            .keylineTint(flightBlue)
        }
    }
}

private struct FlightLockScreen: View {
    let context: ActivityViewContext<FlightActivityAttributes>
    var body: some View {
        let state = context.state
        let old = context.isStale || state.stale || state.expiresAt <= Date()
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label(state.flightNumber, systemImage: "airplane").font(.headline).foregroundStyle(flightBlue)
                Spacer()
                Text("OneStep Flight").font(.caption)
            }
            if old {
                Label(state.staleLabel, systemImage: "clock.badge.exclamationmark").font(.subheadline)
            } else {
                Text(state.nextStep).font(.headline).fixedSize(horizontal: false, vertical: true)
                HStack(alignment: .top, spacing: 20) {
                    VStack(alignment: .leading) {
                        Text(state.gateLabel).font(.caption)
                        Text(state.gate.isEmpty ? state.missingLabel : state.gate).font(.title3.bold())
                    }
                    VStack(alignment: .leading) {
                        Text(state.boardingLabel).font(.caption)
                        Text(state.boardingTime.isEmpty || state.boardingTime == "—" ? state.missingLabel : state.boardingTime).font(.title3.bold())
                    }
                }
            }
            Text(state.updatedLabel + ": " + state.updatedText).font(.caption2)
        }
        .foregroundStyle(flightInk)
        .padding(16)
        .accessibilityElement(children: .combine)
    }
}

@main
struct FlightActivityBundle: WidgetBundle {
    var body: some Widget { FlightActivityWidget() }
}
