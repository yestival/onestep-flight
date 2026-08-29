package com.selene.onestepflight.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import com.selene.onestepflight.dto.AirportResourcesResponse;
import com.selene.onestepflight.dto.AirportResourcesResponse.ContactResource;
import com.selene.onestepflight.dto.AirportResourcesResponse.ContactStatus;
import com.selene.onestepflight.dto.AirportResourcesResponse.MapResource;
import com.selene.onestepflight.service.SerpApiAirportResourcesService;

import jakarta.validation.Validation;

class AssistanceControllerTest {
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		SerpApiAirportResourcesService service = mock(SerpApiAirportResourcesService.class);
		when(service.findResources(any())).thenReturn(new AirportResourcesResponse(
				"STR", "Starport International Airport", "1", "星港國際機場", "星港市",
				MapResource.notFound(),
				new ContactResource(ContactStatus.PHONE_FOUND, "+99 12 3456 7890", "tel:+991234567890",
						"https://starport.test/contact", "https://starport.test/contact", "旅客お問い合わせ",
						"ja", "AI_MODE", "general passenger information", "AI_OFFICIAL_DIRECT",
						true, true, true, false, false),
				3, List.of(), Instant.parse("2026-08-29T00:00:00Z"), false));
		mockMvc = MockMvcBuilders.standaloneSetup(new AssistanceController(service))
				.setValidator(new SpringValidatorAdapter(Validation.buildDefaultValidatorFactory().getValidator()))
				.build();
	}

	@Test
	void validRequestReturnsIndependentMapAndContactShape() throws Exception {
		mockMvc.perform(post("/api/assistance/airport-resources").contentType(MediaType.APPLICATION_JSON)
				.content("{\"airportCode\":\"str\",\"airportName\":\"Starport International Airport\",\"terminal\":\"1\",\"forceRefresh\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.airportCode").value("STR"))
				.andExpect(jsonPath("$.terminal").value("1"))
				.andExpect(jsonPath("$.map.status").value("NOT_FOUND"))
				.andExpect(jsonPath("$.contact.status").value("PHONE_FOUND"))
				.andExpect(jsonPath("$.contact.telUri").value("tel:+991234567890"))
				.andExpect(jsonPath("$.status").doesNotExist());
	}

	@Test
	void invalidAirportCodeReturnsHttp400() throws Exception {
		mockMvc.perform(post("/api/assistance/airport-resources").contentType(MediaType.APPLICATION_JSON)
				.content("{\"airportCode\":\"STR4\"}"))
				.andExpect(status().isBadRequest());
	}
}
