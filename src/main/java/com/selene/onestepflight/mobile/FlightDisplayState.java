package com.selene.onestepflight.mobile;

/** App, Widget and APNs wire contract. All timestamps are integer Unix seconds. */
public record FlightDisplayState(
        String journeyKey, String flightNumber, String flightDate, String departureAirport, String arrivalAirport,
        String dateText, String phase, String actionCode, String nextStep, boolean headingIncludesGate,
        String terminal, String gate, String boardingTime, String departureTime, String departureTimeType,
        String arrivalTime, String arrivalTimeType, String arrivalTerminal, String baggageBelt,
        String locale, String metricOneLabel, String metricOneValue, String metricTwoLabel, String metricTwoValue,
        String metricThreeLabel, String metricThreeValue, String timezoneText, String updatedText,
        String staleLabel, String offlineLabel, String missingLabel, boolean stale, boolean offline,
        long updatedAt, long staleAt, long journeyEndsAt, long snapshotVersion, long clientRevision) {}
