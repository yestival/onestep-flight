package com.selene.onestepflight.service;

import java.util.regex.Pattern;

/** Classifies page audience independently from airport/domain identity. */
public final class PageAudienceClassifier {
	private static final Pattern CORPORATE = Pattern.compile(
			"(?iu)\\b(?:b2b|business|corporate|group strategy|strategy|telecom|telecommunications|network|cybersecurity|"
			+ "recruitment|careers?|jobs?|procurement|supplier|media|press|investors?|advertising|real estate|commercial property|it services?)\\b|"
			+ "招聘|采购|採購|媒体|媒體|投资者|投資者|広報|採用|調達|投資家|채용|조달|투자자|корпоратив|закупк|инвестор");
	private static final Pattern SPECIALIZED = Pattern.compile(
			"(?iu)\\b(?:lost property|lost and found|parking|cargo|freight|police|medical|emergency|airline|lounge|"
			+ "baggage|car rental|taxi|hotel|shop|restaurant|airlines?|drop[ -]?off(?: fees?)?|apcoa)\\b|"
			+ "失物|停车|停車|貨物|货运|駐車場|遺失物|분실물|주차|багаж|парковк");
	private static final Pattern GENERAL_CONTACT = Pattern.compile(
			"(?iu)\\b(?:customer service|contact centre|contact center|airport information|general enquiries|"
			+ "general inquiries|feedback|complaints?|comments?|opinions?|airport switchboard|help centre|help center)\\b|"
			+ "客户服务|客戶服務|一般问询|一般問詢|意见|意見|投诉|投訴|お問い合わせ|ご意見|"
			+ "고객 문의|승객 문의|ผู้โดยสาร|خدمة العملاء|استفسارات المسافرين|обратная связь|reclamaciones|atención al cliente|"
			+ "service clients|réclamations|serviço ao cliente|atendimento ao cliente");
	private static final Pattern PASSENGER_CONTEXT = Pattern.compile(
			"(?iu)\\b(?:passenger|traveller|traveler)\\b|旅客|乘客|승객|пассажир");
	private static final Pattern CONTACT_ONLY = Pattern.compile(
			"(?iu)\\b(?:contact|contacts|contacto|contato|kontakt|help)\\b|联系|聯絡|お問い合わせ|문의|اتصل|تواصل|контакт");

	public PageAudience classify(String url, String title, String snippet, boolean verifiedDomain) {
		if (!verifiedDomain) return PageAudience.THIRD_PARTY_DIRECTORY;
		String descriptive = String.join(" ", value(title), value(snippet));
		String all = descriptive + " " + value(url);
		if (CORPORATE.matcher(all).find()) return PageAudience.CORPORATE_SPECIALIZED;
		if (SPECIALIZED.matcher(all).find()) return PageAudience.PASSENGER_SPECIALIZED;
		if (GENERAL_CONTACT.matcher(descriptive).find()) return PageAudience.PASSENGER_GENERAL;
		if (PASSENGER_CONTEXT.matcher(descriptive).find() && CONTACT_ONLY.matcher(all).find()) {
			return PageAudience.PASSENGER_GENERAL;
		}
		if (CONTACT_ONLY.matcher(all).find()) return PageAudience.UNKNOWN;
		return PageAudience.UNKNOWN;
	}

	private static String value(String value) { return value == null ? "" : value; }
}
