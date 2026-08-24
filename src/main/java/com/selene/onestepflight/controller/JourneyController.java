package com.selene.onestepflight.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.selene.onestepflight.dto.JourneyDecisionResponse;
import com.selene.onestepflight.dto.JourneyContext;
import com.selene.onestepflight.dto.NextActionResponse;
import com.selene.onestepflight.dto.ResolveFlightRequest;
import com.selene.onestepflight.dto.ResolvedFlightContext;
import com.selene.onestepflight.service.FlightConflictConfirmationRequiredException;
import com.selene.onestepflight.service.IpRateLimitService;
import com.selene.onestepflight.service.NextActionService;
import com.selene.onestepflight.service.ResolvedFlightService;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;

@Validated
@RestController
@RequestMapping("/api/journeys")
public class JourneyController {

	private final ResolvedFlightService resolvedFlightService;
	private final NextActionService nextActionService;
	private final IpRateLimitService rateLimitService;

	public JourneyController(ResolvedFlightService resolvedFlightService, NextActionService nextActionService,
			IpRateLimitService rateLimitService) {
		this.resolvedFlightService = resolvedFlightService;
		this.nextActionService = nextActionService;
		this.rateLimitService = rateLimitService;
	}

	@PostMapping("/resolve-flight")
	public ResolvedFlightContext resolveFlight(@Valid @RequestBody ResolveFlightRequest request,
			HttpServletRequest servletRequest) {
		if (!request.demo()) rateLimitService.check(IpRateLimitService.Category.LIVE_FLIGHT, servletRequest);
		return resolvedFlightService.resolve(request);
	}

	@PostMapping("/refresh-flight")
	public ResolvedFlightContext refreshFlight(@RequestBody ResolvedFlightContext snapshot,
			HttpServletRequest servletRequest) {
		if (!snapshot.demo()) rateLimitService.check(IpRateLimitService.Category.LIVE_FLIGHT, servletRequest);
		return resolvedFlightService.refresh(snapshot, true);
	}

	@PostMapping("/next-action")
	public JourneyDecisionResponse getNextAction(@Valid @RequestBody JourneyContext context,
			HttpServletRequest servletRequest) {
		if (!context.demo() && context.refreshRequested()) {
			rateLimitService.check(IpRateLimitService.Category.LIVE_FLIGHT, servletRequest);
		}
		ResolvedFlightContext flight = resolvedFlightService.refresh(
				context.flightSnapshot(), context.refreshRequested());
		if (flight.requiresUserConfirmation() && !context.conflictsConfirmed()) {
			throw new FlightConflictConfirmationRequiredException();
		}
		NextActionResponse nextAction = nextActionService.calculate(flight, context);
		return new JourneyDecisionResponse(flight, nextAction);
	}
}
