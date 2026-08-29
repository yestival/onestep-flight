package com.selene.onestepflight.service;

import org.springframework.stereotype.Component;

import com.selene.onestepflight.dto.JourneyProblemRequest;

@Component
public class JourneyProblemLocalFallback {
	public String answer(JourneyProblemRequest request) {
		return switch (request.locale()) {
			case "zh" -> "先别着急，请保留登机牌和航班信息，并把你刚才说明的情况告诉航空公司工作人员，请他们协助确认下一步安排。";
			case "es" -> "No te preocupes. Conserva tu tarjeta de embarque y los datos del vuelo, y explica al personal de la aerolínea la situación que has descrito para que pueda confirmar el siguiente paso.";
			default -> "Please don’t worry. Keep your boarding pass and flight details, and explain the situation you described to airline staff so they can help confirm the next step.";
		};
	}
}
