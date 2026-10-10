package com.selene.onestepflight;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.selene.onestepflight.config.MobileCorsConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MobileCorsTests {
    @Autowired MockMvc mvc;

    @Test void supportsActualWebViewPreflightsIncludingShareCredentials() throws Exception {
        for (String method : new String[]{"GET", "POST", "PUT", "DELETE"}) {
            mvc.perform(options("/api/share-sessions/fixture")
                    .header("Origin", "capacitor://localhost")
                    .header("Access-Control-Request-Method", method)
                    .header("Access-Control-Request-Headers", "Content-Type,Accept,X-Share-Update-Token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "capacitor://localhost"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }
    }

    @Test void rejectsUnconfiguredAndNullOrigins() throws Exception {
        for (String origin : new String[]{"null", "https://evil.example", "http://localhost:4173"}) {
            mvc.perform(options("/api/journeys/next-action").header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Test void doesNotEnableCorsOnStaticOrOtherRoutes() throws Exception {
        mvc.perform(options("/index.html").header("Origin", "capacitor://localhost")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test void forbidsWildcardNullAndPathConfiguration() {
        for (String origin : new String[]{"*", "null", "https://*.example.com", "https://example.com/path"}) {
            assertThrows(IllegalArgumentException.class, () -> new MobileCorsConfiguration(origin));
        }
    }
}
