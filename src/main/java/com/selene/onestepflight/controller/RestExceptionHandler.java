package com.selene.onestepflight.controller;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.selene.onestepflight.dto.ApiError;
import com.selene.onestepflight.dto.ProviderAggregateError;
import com.selene.onestepflight.service.AllFlightProvidersUnavailableException;
import com.selene.onestepflight.service.FlightNotFoundException;
import com.selene.onestepflight.service.FlightConflictConfirmationRequiredException;
import com.selene.onestepflight.service.ExternalServiceNotConfiguredException;
import com.selene.onestepflight.service.ExternalServiceUnavailableException;
import com.selene.onestepflight.service.InvalidDocumentException;
import com.selene.onestepflight.service.InvalidFlightNumberException;

@RestControllerAdvice
public class RestExceptionHandler {
	@ExceptionHandler(AllFlightProvidersUnavailableException.class)
	public ResponseEntity<ProviderAggregateError> handleAllProvidersUnavailable(
			AllFlightProvidersUnavailableException exception) {
		String code = exception.code();
		HttpStatus status = switch (code) {
			case "FLIGHT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
			case "LIVE_FLIGHT_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
			case "LIVE_FLIGHT_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
			default -> HttpStatus.BAD_GATEWAY;
		};
		String messageZh = switch (code) {
			case "FLIGHT_NOT_FOUND" -> "没有查到这个航班。请检查航空公司代码、航班号和出发日期；如果信息来自上传文件，也可能是文件识别有误。";
			case "LIVE_FLIGHT_TIMEOUT" -> "实时航班查询服务超时，无法据此判断该航班是否存在。请稍后重试。";
			case "LIVE_FLIGHT_AUTH_ERROR" -> "实时航班数据源认证失败，当前无法确认该航班是否存在。请稍后重试。";
			case "LIVE_FLIGHT_RATE_LIMITED" -> "实时航班数据源暂时达到查询限制，当前无法确认该航班是否存在。请稍后重试。";
			default -> "当前无法确认该航班是否存在。请稍后重试。";
		};
		String messageEn = switch (code) {
			case "FLIGHT_NOT_FOUND" -> "No flight was found. Check the airline code, flight number, and departure date; information from an uploaded file may also have been recognized incorrectly.";
			case "LIVE_FLIGHT_TIMEOUT" -> "The live-flight lookup timed out. This does not show that the flight does not exist. Please try again later.";
			case "LIVE_FLIGHT_AUTH_ERROR" -> "Live-flight provider authentication failed, so the flight cannot be verified right now. Please try again later.";
			case "LIVE_FLIGHT_RATE_LIMITED" -> "Live-flight providers are temporarily rate-limited, so the flight cannot be verified right now. Please try again later.";
			default -> "The flight cannot be verified right now. Please try again later.";
		};
		return ResponseEntity.status(status).body(new ProviderAggregateError(
				code, messageZh, messageEn,
				exception.providerResults(), Instant.now()));
	}

	@ExceptionHandler(FlightConflictConfirmationRequiredException.class)
	public ResponseEntity<ApiError> handleConflictConfirmationRequired() {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
				"FLIGHT_CONFLICT_CONFIRMATION_REQUIRED",
				"文件信息与实时航班存在关键冲突。请先停止操作，并向工作人员明确核对。",
				"Critical document and live-flight details conflict. Stop and confirm them with staff first.",
				Instant.now()));
	}

	@ExceptionHandler(FlightNotFoundException.class)
	public ResponseEntity<ApiError> handleFlightNotFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
				"FLIGHT_NOT_FOUND",
				"没有查到这个航班。请检查航空公司代码、航班号和出发日期；如果信息来自上传文件，也可能是文件识别有误。",
				"No flight was found. Check the airline code, flight number, and departure date; information from an uploaded file may also have been recognized incorrectly.",
				Instant.now()));
	}

	@ExceptionHandler(InvalidFlightNumberException.class)
	public ResponseEntity<ApiError> handleInvalidFlightNumber() {
		return ResponseEntity.badRequest().body(new ApiError(
				"INVALID_FLIGHT_NUMBER",
				"航班号格式不正确，请检查后重试。",
				"The flight number format is not valid. Check it and try again.",
				Instant.now()));
	}

	@ExceptionHandler(ExternalServiceNotConfiguredException.class)
	public ResponseEntity<ApiError> handleNotConfigured(ExternalServiceNotConfiguredException exception) {
		boolean nutrient = "NUTRIENT".equals(exception.service());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError(
				nutrient ? "DOCUMENT_EXTRACTION_NOT_CONFIGURED" : "LIVE_FLIGHT_NOT_CONFIGURED",
				nutrient ? "文档识别尚未配置。请手动输入航班号和日期。" : "实时航班查询尚未配置。你仍可使用明确标注的 BA 117 演示航班。",
				nutrient ? "Document recognition is not configured. Enter the flight number and date manually." : "Live flight search is not configured. You can still use the clearly labelled BA 117 demo flight.",
				Instant.now()));
	}

	@ExceptionHandler(ExternalServiceUnavailableException.class)
	public ResponseEntity<ApiError> handleExternalUnavailable(ExternalServiceUnavailableException exception) {
		String code = exception.code();
		boolean nutrient = code.startsWith("NUTRIENT");
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiError(
				code,
				nutrient ? "Nutrient 暂时无法读取这份文件。请重试，或手动输入航班号和日期。" : "SerpApi 暂时没有返回这个日期的完整航班信息。请检查日期后重试，不会自动改用演示数据。",
				nutrient ? "Nutrient cannot read this file right now. Try again or enter the flight number and date manually." : "SerpApi did not return complete flight details for this date. Check the date and try again; demo data will not be substituted.",
				Instant.now()));
	}

	@ExceptionHandler(InvalidDocumentException.class)
	public ResponseEntity<ApiError> handleInvalidDocument(InvalidDocumentException exception) {
		return ResponseEntity.badRequest().body(new ApiError(
				exception.code(),
				"请选择不超过 10MB 的 PDF、JPEG 或 PNG 文件。",
				"Choose a PDF, JPEG, or PNG file no larger than 10MB.",
				Instant.now()));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ApiError> handleMaxUploadSize() {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ApiError(
				"DOCUMENT_TOO_LARGE",
				"文件太大。请选择不超过 10MB 的文件。",
				"The file is too large. Choose a file no larger than 10MB.",
				Instant.now()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation() {
		return ResponseEntity.badRequest().body(new ApiError(
				"JOURNEY_CONTEXT_INVALID",
				"当前步骤信息不完整，请返回确认航班后重试。",
				"The current journey details are incomplete. Return to flight confirmation and try again.",
				Instant.now()));
	}
}
