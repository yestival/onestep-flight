package com.selene.onestepflight.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.selene.onestepflight.dto.AirportResourcesRequest;
import com.selene.onestepflight.dto.AirportResourcesResponse;
import com.selene.onestepflight.service.SerpApiAirportResourcesService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/assistance")
public class AssistanceController {
	private final SerpApiAirportResourcesService airportResourcesService;

	public AssistanceController(SerpApiAirportResourcesService airportResourcesService) {
		this.airportResourcesService = airportResourcesService;
	}

	@PostMapping("/airport-resources")
	public AirportResourcesResponse airportResources(@Valid @RequestBody AirportResourcesRequest request) {
		return airportResourcesService.findResources(request);
	}
}
