package com.selene.onestepflight.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class SharePageController {

	@GetMapping("/share/{viewToken}")
	public String sharedJourney(@PathVariable String viewToken) {
		return "forward:/share.html";
	}
}
