package com.selene.onestepflight.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import com.selene.onestepflight.dto.ActionCode;
import com.selene.onestepflight.dto.ShareSessionRequest;
import com.selene.onestepflight.dto.ShareSessionViewResponse;
import com.selene.onestepflight.dto.Urgency;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "share_session")
class ShareSessionEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Version
	private long version;

	@Column(nullable = false, unique = true, length = 64)
	private String viewTokenHash;

	@Column(nullable = false, length = 64)
	private String updateTokenHash;

	@Column(nullable = false, length = 16)
	private String flightNumber;

	@Column(nullable = false)
	private LocalDate departureDate;

	@Column(nullable = false, length = 8)
	private String departureAirport;

	@Column(nullable = false, length = 8)
	private String arrivalAirport;

	@Convert(converter = ActionCodeAttributeConverter.class)
	@Column(length = 32)
	private ActionCode actionCode;

	@Enumerated(EnumType.STRING)
	@Column(length = 24)
	private Urgency urgency;

	@Column(length = 80)
	private String destination;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "share_session_completed_step", joinColumns = @JoinColumn(name = "share_session_id"))
	@Column(name = "action_code", nullable = false, length = 32)
	@Convert(converter = ActionCodeAttributeConverter.class)
	private Set<ActionCode> completedSteps = EnumSet.noneOf(ActionCode.class);

	@Column(length = 40)
	private String terminal;

	@Column(length = 40)
	private String gate;

	@Column(length = 80)
	private String checkInDeskOrZone;

	@Column(length = 80)
	private String dedicatedBagDropDesk;

	@Column(length = 40)
	private String arrivalBaggageBelt;

	@Column(length = 40)
	private String status;

	@Column(nullable = false)
	private Instant lastUpdated;

	@Column(nullable = false)
	private boolean completed;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false, updatable = false)
	private Instant expiresAt;

	private Instant revokedAt;

	protected ShareSessionEntity() {
	}

	ShareSessionEntity(String viewTokenHash, String updateTokenHash, Instant createdAt, Instant expiresAt) {
		this.viewTokenHash = viewTokenHash;
		this.updateTokenHash = updateTokenHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	void apply(ShareSessionRequest request, Instant serverNow) {
		flightNumber = normalizeFlightNumber(request.flightNumber());
		departureDate = request.departureDate();
		departureAirport = normalizeAirport(request.departureAirport());
		arrivalAirport = normalizeAirport(request.arrivalAirport());
		actionCode = request.actionCode();
		urgency = request.urgency();
		destination = clean(request.destination());
		completedSteps = request.completedSteps() == null || request.completedSteps().isEmpty()
				? EnumSet.noneOf(ActionCode.class)
				: EnumSet.copyOf(request.completedSteps());
		terminal = clean(request.terminal());
		gate = clean(request.gate());
		checkInDeskOrZone = clean(request.checkInDeskOrZone());
		dedicatedBagDropDesk = clean(request.dedicatedBagDropDesk());
		arrivalBaggageBelt = clean(request.arrivalBaggageBelt());
		status = clean(request.status());
		// The server owns the public update timestamp. This keeps it monotonic and
		// prevents a stale/offline or deliberately future client timestamp from
		// making a family snapshot appear newer than the state just accepted.
		lastUpdated = serverNow;
		completed = request.completed();
	}

	ShareSessionViewResponse toView() {
		return new ShareSessionViewResponse(flightNumber, departureDate, departureAirport, arrivalAirport,
				actionCode, urgency, destination, Set.copyOf(completedSteps), terminal, gate,
				checkInDeskOrZone, dedicatedBagDropDesk, arrivalBaggageBelt, status, lastUpdated,
				completed, expiresAt);
	}

	String updateTokenHash() {
		return updateTokenHash;
	}

	Instant expiresAt() {
		return expiresAt;
	}

	Instant revokedAt() {
		return revokedAt;
	}

	void revoke(Instant now) {
		revokedAt = now;
	}

	private static String normalizeFlightNumber(String value) {
		return value.replaceAll("\\s+", "").toUpperCase();
	}

	private static String normalizeAirport(String value) {
		return value.trim().toUpperCase();
	}

	private static String clean(String value) {
		if (value == null) {
			return null;
		}
		String cleaned = value.trim();
		return cleaned.isEmpty() ? null : cleaned;
	}
}
