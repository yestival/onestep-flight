package com.selene.onestepflight.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.selene.onestepflight.service.DemoScenarioFlightDataProvider;
import com.selene.onestepflight.service.DemoRecordingSessionId;
import com.selene.onestepflight.service.RealIntegrationCallStatus;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@ConditionalOnProperty(name = "onestep.demo-recording.enabled", havingValue = "true")
public class DemoRecordingController {

	private final DemoScenarioFlightDataProvider demoScenarios;
	private final RealIntegrationCallStatus integrationCalls;

	public DemoRecordingController(DemoScenarioFlightDataProvider demoScenarios,
			RealIntegrationCallStatus integrationCalls) {
		this.demoScenarios = demoScenarios;
		this.integrationCalls = integrationCalls;
	}

	@GetMapping(value = "/demo-control", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<Resource> controlPage() {
		return privateResource("demo-recording/demo-control.html",
				new MediaType("text", "html", StandardCharsets.UTF_8));
	}

	@GetMapping(value = "/demo-control.css", produces = "text/css")
	public ResponseEntity<Resource> controlStyles() {
		return privateResource("demo-recording/demo-control.css", MediaType.parseMediaType("text/css"));
	}

	@GetMapping(value = "/demo-control.js", produces = "text/javascript")
	public ResponseEntity<Resource> controlScript() {
		return privateResource("demo-recording/demo-control.js", MediaType.parseMediaType("text/javascript"));
	}

	@GetMapping("/api/demo-recording/sessions/{demoSessionId}")
	public ResponseEntity<SessionStatus> session(@PathVariable String demoSessionId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(new SessionStatus(requireUuid(demoSessionId), true));
	}

	@DeleteMapping("/api/demo-recording/sessions/{demoSessionId}/scenario-state")
	public ResponseEntity<Void> clearScenarioState(@PathVariable String demoSessionId) {
		String id = requireUuid(demoSessionId);
		demoScenarios.clearSession(id);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@GetMapping("/api/demo-recording/integration-status")
	public ResponseEntity<RealIntegrationCallStatus.Snapshot> integrationStatus() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(integrationCalls.snapshot());
	}

	private String requireUuid(String value) {
		String normalized = DemoRecordingSessionId.normalize(value);
		if (normalized == null) throw new ResponseStatusException(NOT_FOUND);
		return normalized;
	}

	private ResponseEntity<Resource> privateResource(String path, MediaType contentType) {
		Resource resource = new ClassPathResource(path);
		if (!resource.exists()) throw new ResponseStatusException(NOT_FOUND);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(contentType).body(resource);
	}

	public record SessionStatus(String demoSessionId, boolean enabled) { }
}
