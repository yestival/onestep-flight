package com.selene.onestepflight.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.selene.onestepflight.dto.ActionCode;
import com.selene.onestepflight.dto.FlightChange;
import com.selene.onestepflight.dto.JourneyContext;
import com.selene.onestepflight.dto.JourneyStepType;
import com.selene.onestepflight.dto.NextActionResponse;
import com.selene.onestepflight.dto.ResolvedFlightContext;
import com.selene.onestepflight.dto.Urgency;

@Service
public class NextActionService {
	private final Clock clock;

	public NextActionService() {
		this(Clock.systemUTC());
	}

	NextActionService(Clock clock) {
		this.clock = clock;
	}

	public NextActionResponse calculate(ResolvedFlightContext flight, JourneyContext context) {
		if (context.needsAssistance()) {
			return action(flight, ActionCode.ASK_FOR_HELP,
					"现在请工作人员帮忙", "把这张双语大字卡给附近的机场工作人员看。",
					"Ask a member of staff now", "Show this bilingual large card to a nearby airport staff member.",
					"Airport staff / 机场工作人员", Urgency.URGENT,
					"PASSENGER_REQUESTED_ASSISTANCE", "JOURNEY_CONTEXT", true);
		}

		if (context.boarded()) {
			return afterBoarding(flight, context);
		}

		String status = normalizedStatus(flight.status());
		if ("CANCELLED".equals(status)) {
			return action(flight, ActionCode.ASK_FOR_HELP,
					"航班已取消，请立即找工作人员", "不要继续按原行程前进，请联系航空公司工作人员重新安排。",
					"The flight is cancelled. Ask for help now", "Do not continue with the old plan. Ask airline staff to rearrange your journey.",
					checkInArea(flight), Urgency.URGENT,
					"FLIGHT_CANCELLED", "FLIGHT_STATUS", true);
		}
		DepartureTiming departureTiming = departureTiming(flight, context.departureDate());
		if (departureTiming.dateMismatch()) {
			return action(flight, ActionCode.ASK_FOR_HELP,
					"航班日期和起飞时间需要核对", "当前航班日期与带时区的起飞时间不一致，请先让工作人员核对航班日期。",
					"Confirm the flight date and departure time", "The flight date does not match the offset-aware departure time. Ask staff to confirm the flight date first.",
					gateOrTerminal(flight), Urgency.URGENT,
					"FLIGHT_DATE_TIME_MISMATCH", "FLIGHT_DATE_AND_OFFSET_TIME", true);
		}
		long minutesUntilDeparture = departureTiming.minutesUntilDeparture() == null
				? Long.MAX_VALUE : departureTiming.minutesUntilDeparture();
		if (departureTiming.currentFlightIdentity()
				&& (flight.actualDeparture() != null || status.startsWith("DEPARTED") || status.startsWith("ARRIVED"))) {
			return action(flight, ActionCode.ASK_FOR_HELP,
					"这趟航班已经离港", "点击下方“请工作人员帮忙”，并前往航空公司服务柜台出示给工作人员。",
					"This flight has departed", "Select “Ask a member of staff” below, then go to the airline service desk and show the card to a staff member.",
					"航空公司服务柜台 / Airline service desk", Urgency.URGENT,
					"FLIGHT_ALREADY_DEPARTED", "LIVE_FLIGHT_STATUS", true);
		}
		if (departureTiming.passedByAtLeastFifteenMinutes() && !"BOARDING".equals(status)) {
			return action(flight, ActionCode.ASK_FOR_HELP,
					"这趟航班已经离港", "点击下方“请工作人员帮忙”，并前往航空公司服务柜台出示给工作人员。",
					"This flight has departed", "Select “Ask a member of staff” below, then go to the airline service desk and show the card to a staff member.",
					"航空公司服务柜台 / Airline service desk", Urgency.URGENT,
					"DEPARTURE_TIME_PASSED", "SERVER_TIME_AND_SCHEDULE", true);
		}

		boolean effectivelyCheckedIn = isEffectivelyCheckedIn(context);
		if (!effectivelyCheckedIn) {
			return action(flight, ActionCode.CHECK_IN,
					"先去办理值机", "请前往航空公司值机区域，出示护照或旅行证件，并现场确认柜台。",
					"Check in first", "Go to the airline check-in area, show your passport or travel document, and confirm the counter on site.",
					checkInArea(flight), Urgency.SOON,
					"CHECK_IN_NOT_COMPLETED", "JOURNEY_CONTEXT", true);
		}

		if (context.hasCheckedBaggage() == null) {
			return baggageQuestion(flight);
		}

		if (context.hasCheckedBaggage() && !context.bagDropped()) {
			boolean dedicatedDeskKnown = !isBlank(flight.dedicatedBagDropDesk());
			return action(flight, ActionCode.BAG_DROP,
					"现在去托运行李", dedicatedDeskKnown
							? "请前往已明确公布的行李托运柜台，把需要托运的行李交给工作人员。"
							: "实时来源没有明确公布专用行李托运柜台。请先到航空公司值机区域，再请工作人员指明托运位置。",
					"Drop off your checked baggage now", dedicatedDeskKnown
							? "Go to the explicitly published bag-drop desk and give your checked baggage to staff."
							: "No live source explicitly published a dedicated bag-drop desk. Go to the airline check-in area and ask staff to point out bag drop.",
					bagDropArea(flight), Urgency.SOON,
					"CHECKED_BAGGAGE_NOT_DROPPED", "JOURNEY_CONTEXT", !dedicatedDeskKnown);
		}

		if (!context.securityCleared()) {
			return action(flight, ActionCode.SECURITY,
					"现在前往安检", "准备好登机牌和旅行证件，跟随机场内的安检标志。",
					"Go to security now", "Have your boarding pass and travel document ready, then follow the airport security signs.",
					terminalArea(flight, "安检 / Security"), Urgency.SOON,
					"SECURITY_NOT_COMPLETED", "JOURNEY_CONTEXT", true);
		}

		if (flight.timeContext() != null && flight.timeContext().stale() && minutesUntilDeparture <= 90) {
			return action(flight, ActionCode.ASK_FOR_HELP,
					"实时信息较旧，请工作人员确认", "起飞时间已经临近，但实时数据超过 10 分钟没有可靠更新。请先让工作人员核对。",
					"Live information is old. Ask staff to confirm", "Departure is getting close, but the live data is more than 10 minutes old. Ask staff to verify it first.",
					terminalArea(flight, "航班信息屏 / Flight information screen"), Urgency.URGENT,
					"STALE_DATA_NEAR_DEPARTURE", "SERVER_TIME_AND_DATA_AGE", true);
		}

		if (("DELAYED".equals(status) || flight.delayMinutes() >= 45) && !"BOARDING".equals(status)) {
			return action(flight, ActionCode.WAIT,
					"航班明显延误，先不要继续前往登机口", "必要步骤已经完成。请留在能看到屏幕、听到广播的位置，并等待新的实时信息。",
					"The flight is significantly delayed. Do not continue to the gate yet", "Required steps are complete. Stay where you can see screens and hear announcements, then wait for a live update.",
					terminalArea(flight, "航班信息屏附近 / Near a flight information screen"), Urgency.NORMAL,
					"SIGNIFICANT_DELAY_AFTER_REQUIRED_STEPS", "LIVE_FLIGHT_STATUS", true);
		}

		if (isBlank(flight.gate())) {
			if (minutesUntilDeparture <= 90 || "BOARDING".equals(status)) {
				return action(flight, ActionCode.ASK_FOR_HELP,
						"登机口还未公布，请工作人员帮忙", "时间临近，请工作人员帮你确认登机口。",
						"The gate is not posted. Ask a member of staff", "Departure is getting close. Ask staff to confirm your gate.",
						flight.terminal(), Urgency.URGENT,
						"GATE_MISSING_NEAR_DEPARTURE", "FLIGHT_STATUS_AND_SERVER_TIME", true);
			}
			return action(flight, ActionCode.WAIT,
					"先在航站楼内等候", "登机口尚未公布，请留在能看到屏幕、听到广播的区域。",
					"Wait in the terminal", "The gate is not posted yet. Stay where you can see screens and hear announcements.",
					flight.terminal(), Urgency.NORMAL,
					"GATE_NOT_PUBLISHED", "FLIGHT_STATUS", true);
		}

		FlightChange latestGateChange = latestChange(flight, "GATE_CHANGED");
		FlightChange latestTerminalChange = latestChange(flight, "TERMINAL_CHANGED");
		boolean terminalChanged = latestTerminalChange != null || (!isBlank(context.previousTerminal())
				&& !FlightFieldSemantics.equivalent("terminal", context.previousTerminal(), flight.terminal()));
		if (terminalChanged && !context.atGate()) {
			String oldTerminal = latestTerminalChange == null ? context.previousTerminal() : latestTerminalChange.oldValue();
			return action(flight, ActionCode.GO_TO_GATE,
					"航站楼已从 " + oldTerminal + " 改为 " + flight.terminal(), "请不要去原来的航站楼，先向工作人员确认跨航站楼路线。",
					"The terminal changed from " + oldTerminal + " to " + flight.terminal(), "Do not go to the old terminal. Ask staff to confirm the route between terminals first.",
					terminalArea(flight, flight.gate()), Urgency.URGENT,
					"TERMINAL_CHANGED", "LIVE_FLIGHT_UPDATE", true);
		}
		boolean gateChanged = latestGateChange != null || (!isBlank(context.previousGate())
				&& !FlightFieldSemantics.equivalent("gate", context.previousGate(), flight.gate()));
		if (gateChanged) {
			String oldGate = latestGateChange == null ? context.previousGate() : latestGateChange.oldValue();
			return action(flight, ActionCode.GO_TO_GATE,
					"登机口已从 " + oldGate + " 改为 " + flight.gate(), "不要去原来的登机口，请跟随标志前往新登机口。",
					"The gate changed from " + oldGate + " to " + flight.gate(), "Do not go to the old gate. Follow the signs to the new gate.",
					flight.gate(), Urgency.URGENT,
					"GATE_CHANGED", "LIVE_FLIGHT_UPDATE", true);
		}

		if (!context.atGate()) {
			return action(flight, ActionCode.GO_TO_GATE,
					"现在前往 " + flight.gate() + " 登机口", "请跟随机场标志前往登机口。",
					"Go to gate " + flight.gate() + " now", "Follow the airport signs to the gate.",
					flight.gate(), "BOARDING".equals(status) || minutesUntilDeparture <= 75 ? Urgency.URGENT : Urgency.SOON,
					"SECURITY_COMPLETED_GATE_AVAILABLE", "FLIGHT_STATUS_AND_JOURNEY_CONTEXT", flight.demo());
		}

		if ("BOARDING".equals(status) || minutesUntilDeparture <= 45) {
			return action(flight, ActionCode.BOARD_NOW,
					"现在准备登机", "请留在登机队伍中，准备好登机牌和旅行证件。",
					"Board now", "Stay in the boarding line with your boarding pass and travel document ready.",
					flight.gate(), Urgency.URGENT,
					"BOARDING_WINDOW_OPEN", "FLIGHT_STATUS_AND_SERVER_TIME", flight.demo());
		}

		return action(flight, ActionCode.WAIT,
				"在登机口附近等候", "你已经到达登机口，请坐在能看到屏幕、听到广播的地方。",
				"Wait near the gate", "You are at the gate. Sit where you can see screens and hear announcements.",
				flight.gate(), Urgency.NORMAL,
				"AT_GATE_BEFORE_BOARDING", "JOURNEY_CONTEXT_AND_SERVER_TIME", true);
	}

	boolean isEffectivelyCheckedIn(JourneyContext context) {
		return context.checkedIn();
	}

	private NextActionResponse afterBoarding(ResolvedFlightContext flight, JourneyContext context) {
		if (!context.landed()) {
			boolean hasCheckedBaggage = Boolean.TRUE.equals(context.hasCheckedBaggage());
			return action(flight, ActionCode.IN_FLIGHT,
					"已经登机", hasCheckedBaggage
							? "抵达后，先查看行李提取屏幕。飞行中请留意机上广播和机组人员指引。"
							: "抵达后，按照 Arrivals / Exit 指示前往出口。飞行中请留意机上广播和机组人员指引。",
					"You are on board", hasCheckedBaggage
							? "After arrival, check the baggage claim screens first. During the flight, follow cabin crew announcements."
							: "After arrival, follow the Arrivals / Exit signs. During the flight, follow cabin crew announcements.",
					arrivalAirport(flight), Urgency.NORMAL,
					"PASSENGER_BOARDED", "JOURNEY_CONTEXT_AND_SAVED_FLIGHT_DATA", false);
		}

		if (Boolean.TRUE.equals(context.hasCheckedBaggage()) && !context.baggageCollected()) {
			boolean baggageBeltKnown = !isBlank(flight.arrivalBaggageBelt());
			return action(flight, ActionCode.BAGGAGE_CLAIM,
					"现在去领取托运行李", baggageBeltKnown
							? "请核对到达屏幕上的航班号后，再在这个行李转盘等待行李。"
							: "请查看到达屏幕，找到你的航班号对应的行李转盘。",
					"Collect your checked baggage now", baggageBeltKnown
							? "Check the flight number on the arrivals screen before waiting at this baggage belt."
							: "Check the arrivals screen to find the baggage belt for your flight.",
					baggageBeltKnown
							? "行李转盘 " + flight.arrivalBaggageBelt() + " / Baggage belt " + flight.arrivalBaggageBelt()
							: "行李提取区 / Baggage claim",
					Urgency.SOON, "PASSENGER_LANDED_CHECKED_BAGGAGE_PENDING",
					baggageBeltKnown ? "LIVE_ARRIVAL_DATA_AND_JOURNEY_CONTEXT" : "JOURNEY_CONTEXT",
					true);
		}

		if (!context.exitedAirport()) {
			return action(flight, ActionCode.EXIT_AIRPORT,
					"现在前往到达出口", "按照 Arrivals / Exit 指示前往出口。",
					"Go to the arrivals exit now", "Follow the Arrivals / Exit signs to the exit.",
					"Arrivals / Exit", Urgency.NORMAL,
					"ARRIVAL_STEPS_COMPLETED_EXIT_PENDING", "JOURNEY_CONTEXT", false);
		}

		return complete(flight);
	}

	private NextActionResponse baggageQuestion(ResolvedFlightContext flight) {
		return response(flight, JourneyStepType.BAGGAGE_QUESTION, null,
				"你有需要托运的行李吗？", "随身携带的小包不算托运行李。请选择最符合你情况的一项。",
				"Do you have baggage to check?", "A small carry-on bag does not count as checked baggage. Choose the option that fits you.",
				null, Urgency.NORMAL, "BAGGAGE_STATUS_REQUIRED", "JOURNEY_CONTEXT", true);
	}

	private NextActionResponse complete(ResolvedFlightContext flight) {
		return response(flight, JourneyStepType.COMPLETE, null,
				"你已经完成乘机指引", "你已经到达机场出口，本次一步乘机指引已完成。",
				"Your flight guidance is complete", "You have reached the airport exit and completed this OneStep Flight journey.",
				null, Urgency.NORMAL, "PASSENGER_EXITED_AIRPORT", "JOURNEY_CONTEXT", flight.demo());
	}

	private NextActionResponse action(ResolvedFlightContext flight, ActionCode code,
			String titleZh, String descriptionZh, String titleEn, String descriptionEn,
			String destination, Urgency urgency, String reason, String triggeredBy,
			boolean needsOnSiteConfirmation) {
		return response(flight, JourneyStepType.ACTION, code, titleZh, descriptionZh, titleEn,
				descriptionEn, destination, urgency, reason, triggeredBy, needsOnSiteConfirmation);
	}

	private NextActionResponse response(ResolvedFlightContext flight, JourneyStepType stepType, ActionCode code,
			String titleZh, String descriptionZh, String titleEn, String descriptionEn,
			String destination, Urgency urgency, String reason, String triggeredBy,
			boolean needsOnSiteConfirmation) {
		return new NextActionResponse(stepType, code, titleZh, descriptionZh, titleEn, descriptionEn,
				destination, urgency, reason, triggeredBy, flight.liveDataSource(),
				flight.live() ? "LIVE_PROVIDER_WITHOUT_FIELD_CONFIDENCE"
						: "DOCUMENT_FALLBACK".equals(flight.liveDataSource())
								? "DOCUMENT_FALLBACK_UNVERIFIED"
								: flight.liveDataSource() != null && flight.liveDataSource().startsWith("MERGED[")
										? "SCHEDULE_PROVIDER_UNVERIFIED"
										: flight.demoScenario() == null ? "DEMO_FIXTURE" : "SIMULATED_SCENARIO",
				null,
				needsOnSiteConfirmation,
				flight.timeContext() == null ? Instant.now(clock) : flight.timeContext().serverNow());
	}

	private DepartureTiming departureTiming(ResolvedFlightContext flight, LocalDate selectedDepartureDate) {
		// Recompute from the current refreshed flight. Do not trust a separately
		// serialized effectiveDeparture value from an older browser snapshot.
		OffsetDateTime effectiveDeparture = firstNonNull(flight.actualDeparture(),
				flight.estimatedDeparture(), flight.scheduledDeparture());
		OffsetDateTime departureDateAnchor = flight.scheduledDeparture() == null
				? effectiveDeparture : flight.scheduledDeparture();
		LocalDate refreshedDepartureDate = flight.departureDate();
		LocalDate currentSelectedDate = selectedDepartureDate == null
				? refreshedDepartureDate : selectedDepartureDate;
		boolean identityDatesPresent = currentSelectedDate != null && refreshedDepartureDate != null;
		boolean dateMismatch = identityDatesPresent
				&& !currentSelectedDate.equals(refreshedDepartureDate);
		boolean currentFlightIdentity = identityDatesPresent && !dateMismatch;
		if (currentFlightIdentity && departureDateAnchor != null) {
			LocalDate departureLocalDate = departureDateAnchor.toInstant()
					.atOffset(departureOffset(flight, departureDateAnchor)).toLocalDate();
			dateMismatch = !currentSelectedDate.equals(departureLocalDate);
			currentFlightIdentity = !dateMismatch;
		}
		Instant now = Instant.now(clock);
		Long minutesUntilDeparture = !currentFlightIdentity || effectiveDeparture == null
				? null : Duration.between(now, effectiveDeparture.toInstant()).toMinutes();
		boolean passedByAtLeastFifteenMinutes = currentFlightIdentity && effectiveDeparture != null
				&& Duration.between(effectiveDeparture.toInstant(), now).compareTo(Duration.ofMinutes(15)) >= 0;
		return new DepartureTiming(minutesUntilDeparture, currentFlightIdentity, dateMismatch,
				passedByAtLeastFifteenMinutes);
	}

	private ZoneOffset departureOffset(ResolvedFlightContext flight, OffsetDateTime fallback) {
		String configuredOffset = flight.timeContext() == null ? null : flight.timeContext().departureUtcOffset();
		if (configuredOffset != null && !configuredOffset.isBlank()) {
			try {
				return ZoneOffset.of(configuredOffset.trim());
			} catch (DateTimeException ignored) {
				// A malformed client snapshot cannot replace the offset carried by
				// the freshly resolved provider time.
			}
		}
		return fallback.getOffset();
	}

	private String normalizedStatus(String status) {
		return status == null ? "UNKNOWN" : status.trim().toUpperCase(Locale.ROOT);
	}

	private String checkInArea(ResolvedFlightContext flight) {
		if (!flight.checkInCounterNeedsConfirmation() && !isBlank(flight.checkInCounter())) {
			return flight.checkInCounter();
		}
		return terminalArea(flight, "航空公司值机区域 / Airline check-in area");
	}

	private String bagDropArea(ResolvedFlightContext flight) {
		if (!isBlank(flight.dedicatedBagDropDesk())) return flight.dedicatedBagDropDesk();
		if (!isBlank(flight.checkInDeskOrZone())) {
			return flight.checkInDeskOrZone() + " · 请询问行李托运位置 / Ask for bag drop";
		}
		return terminalArea(flight, "航空公司值机区，请询问行李托运位置 / Airline check-in area; ask for bag drop");
	}

	private String gateOrTerminal(ResolvedFlightContext flight) {
		return isBlank(flight.gate()) ? flight.terminal() : flight.gate();
	}

	private String arrivalAirport(ResolvedFlightContext flight) {
		if (!isBlank(flight.arrivalAirportName()) && !isBlank(flight.arrivalAirport())) {
			return flight.arrivalAirportName() + " (" + flight.arrivalAirport() + ")";
		}
		return isBlank(flight.arrivalAirportName()) ? flight.arrivalAirport() : flight.arrivalAirportName();
	}

	private String terminalArea(ResolvedFlightContext flight, String area) {
		return isBlank(flight.terminal()) ? area : flight.terminal() + " · " + area;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private FlightChange latestChange(ResolvedFlightContext flight, String type) {
		if (flight.latestChanges() == null) return null;
		return flight.latestChanges().stream()
				.filter(change -> type.equals(change.type()))
				.findFirst()
				.orElse(null);
	}

	@SafeVarargs
	private final <T> T firstNonNull(T... values) {
		for (T value : values) if (value != null) return value;
		return null;
	}

	private record DepartureTiming(
			Long minutesUntilDeparture,
			boolean currentFlightIdentity,
			boolean dateMismatch,
			boolean passedByAtLeastFifteenMinutes) {
	}
}
