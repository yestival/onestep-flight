package com.selene.onestepflight.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts long phone candidates only when a passenger/general-contact role is locally bound in the same clause. */
public final class PassengerContactPhoneExtractor {
	private static final Pattern ROLE_BOUND_PHONE = Pattern.compile(
			"(?iu)(?:\\b(?:call\\s+us|contact\\s+(?:centre|center)|customer\\s+service|general\\s+enquiries|"
			+ "general\\s+inquiries|airport\\s+information|airport\\s+switchboard|passenger\\s+(?:enquiries|inquiries))\\b|"
			+ "客户服务|客戶服務|一般问询|一般問詢|旅客咨询|旅客諮詢|お問い合わせ|고객\\s*문의|승객\\s*문의|"
			+ "خدمة العملاء|استفسارات المسافرين|обратная связь|atención al cliente|service clients)"
			+ "[^.!?;\\r\\n]{0,100}?(\\+?\\d[\\d\\s()\\-/]{5,}\\d)");

	public List<String> extract(String title, String snippet) {
		String text = Normalizer.normalize(String.join(". ", value(title), value(snippet)), Normalizer.Form.NFKC)
				.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
		Matcher matcher = ROLE_BOUND_PHONE.matcher(text);
		LinkedHashSet<String> values = new LinkedHashSet<>();
		while (matcher.find() && values.size() < 4) {
			String candidate = matcher.group(1).trim();
			int digits = candidate.replaceAll("\\D", "").length();
			if (digits > 6) values.add(candidate);
		}
		return List.copyOf(new ArrayList<>(values));
	}

	private static String value(String value) { return value == null ? "" : value; }
}
