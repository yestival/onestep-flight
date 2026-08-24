package com.selene.onestepflight.dto;

public record NextActionContext(
		boolean checkedIn,
		boolean hasCheckedBaggage,
		boolean bagDropped,
		boolean securityCleared,
		boolean atGate,
		boolean needsAssistance,
		int minutesUntilDeparture) {
}
