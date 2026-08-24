package com.selene.onestepflight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OnestepFlightApplication {

	public static void main(String[] args) {
		// Honor the operating system's configured proxy unless the launcher made an
		// explicit choice. This must happen before any JDK networking class is
		// initialized; no proxy address or credentials are stored or logged.
		if (System.getProperty("java.net.useSystemProxies") == null) {
			System.setProperty("java.net.useSystemProxies", "true");
		}
		SpringApplication.run(OnestepFlightApplication.class, args);
	}

}
