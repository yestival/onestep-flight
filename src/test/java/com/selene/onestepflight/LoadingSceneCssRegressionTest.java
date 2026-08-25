package com.selene.onestepflight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class LoadingSceneCssRegressionTest {

    @Test
    void loadingPlaneCrossesTheCenteredSceneWithoutChangingMotionPreferences() throws IOException {
        String css = new ClassPathResource("static/styles.css")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(!css.contains("margin:-10px -15vw 35px"),
                "The loading scene must not retain the wide negative horizontal margin");
        assertTrue(css.contains(".loading-scene{margin:-10px 0 35px}"));
        assertTrue(css.contains("--loading-plane-half-width:102.5px"));
        assertTrue(css.contains("--loading-plane-half-width:87.5px"));
        assertTrue(css.contains("50%{left:calc(50% - var(--loading-plane-half-width))"),
                "The plane's visual center must cross the scene's 50% point");
        assertTrue(css.contains("animation:fly-curved 2.9s cubic-bezier(.18,.72,.25,1) forwards"));
        assertTrue(css.contains(".loading-plane{display:block;left:50%;top:27%;animation:none!important;"
                + "transform:translateX(-50%) rotate(-2deg)}"));
    }
}
