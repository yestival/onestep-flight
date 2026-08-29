package com.selene.onestepflight.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.selene.onestepflight.dto.JourneyProblemRequest;
import com.selene.onestepflight.dto.JourneyProblemResponse;
import com.selene.onestepflight.service.SerpApiJourneyProblemService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/assistance")
public class JourneyProblemController {
	private final SerpApiJourneyProblemService service;

	public JourneyProblemController(SerpApiJourneyProblemService service) {
		this.service = service;
	}

	@PostMapping("/journey-problem")
	public JourneyProblemResponse solve(@Valid @RequestBody JourneyProblemRequest request) {
		return service.solve(request);
	}
}
