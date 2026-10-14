package com.selene.onestepflight;

import android.os.Handler;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.RequiresDevice;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real phone TTS service without clearing any application data. */
@RequiresDevice
public class SpeechLifecycleTest {
    private final Handler main = new Handler(Looper.getMainLooper());
    @Test public void bundledVoicesWorkWithoutAnySystemEngine() throws Exception {
        for (String[] phrase : new String[][] {{"OneStep Flight. Go to gate B32.", "en-US"},
            {"一步乘机。请前往 B32 登机口。", "zh-CN"}, {"OneStep Flight. Ve a la puerta B32.", "es-ES"}}) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> failure = new AtomicReference<>();
            FlightSpeech speech = new FlightSpeech(ApplicationProvider.getApplicationContext(), true);
            try {
                main.post(() -> speech.speak(phrase[0], phrase[1], new FlightSpeech.Completion() {
                    @Override public void complete(boolean cancelled) { if (cancelled) failure.set("Unexpected cancellation"); done.countDown(); }
                    @Override public void failed(String code) { failure.set(code); done.countDown(); }
                }));
                assertTrue("Bundled voice timed out for " + phrase[1], done.await(35, TimeUnit.SECONDS));
                assertNull("Bundled voice failed for " + phrase[1], failure.get());
            } finally { main.post(speech::shutdown); }
        }
    }
    @Test public void bundledSpeechCanStopAndRestartWithoutOldCallbacks() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger stopped = new AtomicInteger();
        AtomicReference<String> failure = new AtomicReference<>();
        FlightSpeech speech = new FlightSpeech(ApplicationProvider.getApplicationContext(), true);
        main.post(() -> {
            speech.speak("This cancelled text must never replace the next reading.", "en-US", new FlightSpeech.Completion() {
                @Override public void complete(boolean cancelled) { if (cancelled) stopped.incrementAndGet(); else failure.set("Old reading completed"); }
                @Override public void failed(String code) { failure.set(code); }
            });
            speech.stop();
            speech.speak("继续指引。", "zh-CN", new FlightSpeech.Completion() {
                @Override public void complete(boolean cancelled) { if (cancelled) failure.set("New reading cancelled"); done.countDown(); }
                @Override public void failed(String code) { failure.set(code); done.countDown(); }
            });
        });
        try { assertTrue(done.await(35, TimeUnit.SECONDS)); assertEquals(1, stopped.get()); assertNull(failure.get()); }
        finally { main.post(speech::shutdown); }
    }
    @Test public void englishChineseAndSpanishCompleteThroughTheInstalledEngine() throws Exception {
        for (String[] phrase : new String[][] {{"OneStep Flight. Go to your gate.", "en-US"},
            {"一步乘机。请前往登机口。", "zh-CN"}, {"OneStep Flight. Ve a la puerta.", "es-ES"}}) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> failure = new AtomicReference<>();
            FlightSpeech speech = new FlightSpeech(ApplicationProvider.getApplicationContext());
            try {
                main.post(() -> speech.speak(phrase[0], phrase[1], new FlightSpeech.Completion() {
                    @Override public void complete(boolean cancelled) { if (cancelled) failure.set("Unexpected cancellation"); done.countDown(); }
                    @Override public void failed(String code) { failure.set(code); done.countDown(); }
                }));
                assertTrue("TTS completion timed out for " + phrase[1], done.await(35, TimeUnit.SECONDS));
                assertNull("Native TTS failed for " + phrase[1], failure.get());
            } finally { main.post(speech::shutdown); }
        }
    }
    @Test public void stoppingDuringInitializationCompletesOnceAndAllowsRetry() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<String> failure = new AtomicReference<>();
        FlightSpeech speech = new FlightSpeech(ApplicationProvider.getApplicationContext());
        main.post(() -> {
            speech.speak("This must be cancelled.", "en-US", new FlightSpeech.Completion() {
                @Override public void complete(boolean cancelled) { completions.incrementAndGet(); if (!cancelled) failure.set("Not cancelled"); }
                @Override public void failed(String code) { failure.set(code); }
            });
            speech.stop();
            speech.speak("Reading resumed.", "en-US", new FlightSpeech.Completion() {
                @Override public void complete(boolean cancelled) { if (cancelled) failure.set("Retry cancelled"); done.countDown(); }
                @Override public void failed(String code) { failure.set(code); done.countDown(); }
            });
        });
        try {
            assertTrue(done.await(35, TimeUnit.SECONDS));
            assertEquals(1, completions.get());
            assertNull(failure.get());
        } finally { main.post(speech::shutdown); }
    }
}
