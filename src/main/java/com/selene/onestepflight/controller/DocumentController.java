package com.selene.onestepflight.controller;

import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.service.BoardingPassDocumentProcessor;
import com.selene.onestepflight.service.InvalidDocumentException;
import com.selene.onestepflight.service.IpRateLimitService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

	private static final long MAX_FILE_SIZE = 10L * 1024L * 1024L;
	private static final Set<String> ALLOWED_TYPES = Set.of(
			MediaType.APPLICATION_PDF_VALUE, MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE);

	private final BoardingPassDocumentProcessor documentProcessor;
	private final IpRateLimitService rateLimitService;

	public DocumentController(BoardingPassDocumentProcessor documentProcessor,
			IpRateLimitService rateLimitService) {
		this.documentProcessor = documentProcessor;
		this.rateLimitService = rateLimitService;
	}

	@PostMapping(path = {"/travel-document", "/boarding-pass"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public BoardingPassExtractionResponse extractTravelDocument(@RequestPart("file") MultipartFile file,
			HttpServletRequest servletRequest) {
		rateLimitService.check(IpRateLimitService.Category.DOCUMENT_UPLOAD, servletRequest);
		if (file.isEmpty()) throw new InvalidDocumentException("DOCUMENT_EMPTY");
		if (file.getSize() > MAX_FILE_SIZE) throw new InvalidDocumentException("DOCUMENT_TOO_LARGE");
		String contentType = file.getContentType();
		if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
			throw new InvalidDocumentException("DOCUMENT_TYPE_NOT_ALLOWED");
		}
		try {
			return documentProcessor.extract(file.getBytes(), safeFilename(file.getOriginalFilename()), contentType);
		} catch (java.io.IOException exception) {
			throw new InvalidDocumentException("DOCUMENT_READ_FAILED");
		}
	}

	private String safeFilename(String originalFilename) {
		if (originalFilename == null || originalFilename.isBlank()) return "travel-document";
		return java.nio.file.Path.of(originalFilename).getFileName().toString();
	}
}
