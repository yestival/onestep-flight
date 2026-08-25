package com.selene.onestepflight.controller;

import java.time.LocalDate;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.selene.onestepflight.dto.FlightResponse;
import com.selene.onestepflight.service.FlightService;

@RestController
@RequestMapping("/api/flights")
public class FlightController {

	private final FlightService flightService;

	public FlightController(FlightService flightService) {
		this.flightService = flightService;
	}

	@GetMapping("/{flightNumber}")
	public FlightResponse getFlight(
			@PathVariable String flightNumber,
			@RequestParam(required = false) LocalDate departureDate,
			@RequestParam(defaultValue = "true") boolean demo,
			@RequestParam(defaultValue = "false") boolean refresh) {
		return flightService.getFlight(flightNumber, departureDate, demo, refresh);
	}
}
