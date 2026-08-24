package com.selene.onestepflight.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.selene.onestepflight.dto.ShareSessionCreationResponse;
import com.selene.onestepflight.dto.ShareSessionRequest;
import com.selene.onestepflight.dto.ShareSessionViewResponse;
import com.selene.onestepflight.service.ShareSessionService;
import com.selene.onestepflight.service.IpRateLimitService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/share-sessions")
public class ShareSessionController {
	private static final String SHARE_TOKEN_PATTERN = "[A-Za-z0-9_-]{43}";

	private final ShareSessionService service;
	private final IpRateLimitService rateLimitService;

	public ShareSessionController(ShareSessionService service, IpRateLimitService rateLimitService) {
		this.service = service;
		this.rateLimitService = rateLimitService;
	}

	@PostMapping
	public ResponseEntity<ShareSessionCreationResponse> create(@Valid @RequestBody ShareSessionRequest request,
			HttpServletRequest servletRequest) {
		rateLimitService.check(IpRateLimitService.Category.SHARE_CREATE, servletRequest);
		return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.create(request));
	}

	@GetMapping("/{viewToken}")
	public ResponseEntity<ShareSessionViewResponse> get(
			@PathVariable @Pattern(regexp = SHARE_TOKEN_PATTERN) String viewToken) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(viewToken));
	}

	@PutMapping("/{viewToken}")
	public ResponseEntity<ShareSessionViewResponse> update(
			@PathVariable @Pattern(regexp = SHARE_TOKEN_PATTERN) String viewToken,
			@RequestHeader("X-Share-Update-Token") @Pattern(regexp = SHARE_TOKEN_PATTERN) String updateToken,
			@Valid @RequestBody ShareSessionRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.update(viewToken, updateToken, request));
	}

	@DeleteMapping("/{viewToken}")
	public ResponseEntity<Void> stop(
			@PathVariable @Pattern(regexp = SHARE_TOKEN_PATTERN) String viewToken,
			@RequestHeader("X-Share-Update-Token") @Pattern(regexp = SHARE_TOKEN_PATTERN) String updateToken) {
		service.stop(viewToken, updateToken);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}
}
