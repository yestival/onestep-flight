package com.selene.onestepflight.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** Detects a reliable native text layer without rendering pages or running OCR. */
final class PdfInputClassifier {

	enum Type {
		NATIVE_TEXT,
		IMAGE_OR_MIXED
	}

	record Analysis(Type type, List<String> pageTexts) {
		Analysis {
			type = type == null ? Type.IMAGE_OR_MIXED : type;
			pageTexts = pageTexts == null ? List.of() : List.copyOf(pageTexts);
		}

		static Analysis imageOrMixed() {
			return new Analysis(Type.IMAGE_OR_MIXED, List.of());
		}
	}

	private static final int MIN_LETTERS_OR_DIGITS_PER_PAGE = 32;
	private static final int MIN_WORDS_PER_PAGE = 5;

	Analysis analyze(byte[] pdfBytes) {
		if (pdfBytes == null || pdfBytes.length == 0) return Analysis.imageOrMixed();
		try (PDDocument document = Loader.loadPDF(pdfBytes)) {
			if (document.getNumberOfPages() == 0) return Analysis.imageOrMixed();
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			List<String> pageTexts = new ArrayList<>();
			boolean nativeText = true;
			for (int page = 1; page <= document.getNumberOfPages(); page++) {
				stripper.setStartPage(page);
				stripper.setEndPage(page);
				String pageText = stripper.getText(document);
				pageTexts.add(pageText == null ? "" : pageText);
				if (!hasReliableText(pageText)) nativeText = false;
			}
			return new Analysis(nativeText ? Type.NATIVE_TEXT : Type.IMAGE_OR_MIXED, pageTexts);
		} catch (IOException | RuntimeException exception) {
			// A malformed, encrypted, or otherwise unreadable PDF must stay on the
			// robust Understand path; local classification is never a third extractor.
			return Analysis.imageOrMixed();
		}
	}

	private boolean hasReliableText(String text) {
		if (text == null || text.isBlank()) return false;
		long lettersOrDigits = text.codePoints().filter(Character::isLetterOrDigit).count();
		long words = text.trim().split("\\s+").length;
		return lettersOrDigits >= MIN_LETTERS_OR_DIGITS_PER_PAGE && words >= MIN_WORDS_PER_PAGE;
	}
}
