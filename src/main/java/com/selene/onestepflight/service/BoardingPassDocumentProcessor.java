package com.selene.onestepflight.service;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;

/** Adapter boundary for document extraction providers such as Nutrient DWS. */
public interface BoardingPassDocumentProcessor {

	BoardingPassExtractionResponse extract(byte[] documentBytes, String filename, String contentType);
}
