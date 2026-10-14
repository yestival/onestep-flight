package com.selene.onestepflight;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

/** Main-thread, lazy TTS. Its callbacks never hold application initialization. */
final class FlightSpeech {
    interface Completion {
        void complete(boolean cancelled);
        void failed(String code);
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OfflineSpeech offline;
    private final boolean bundledOnly;
    private TextToSpeech engine;
    private boolean ready;
    private long engineGeneration;
    private Completion completion;
    private String text = "", language = "", requestId = "", lastChunkId = "";
    private String selectedEngine;
    private final Set<String> attemptedEngines = new HashSet<>();
    private Runnable initializationTimeout, playbackTimeout;

    FlightSpeech(Context context) { this(context, false); }
    FlightSpeech(Context context, boolean bundledOnly) {
        this.context = context.getApplicationContext(); this.bundledOnly = bundledOnly;
        offline = new OfflineSpeech(context);
    }

    void speak(String value, String locale, Completion callback) {
        stop();
        if (value == null || value.trim().isEmpty() || value.length() > 16000) { callback.failed("SPEECH_INVALID_TEXT"); return; }
        text = value; language = locale; completion = callback; requestId = UUID.randomUUID().toString();
        attemptedEngines.clear();
        if (selectedEngine != null) attemptedEngines.add(selectedEngine);
        if (bundledOnly) { playOffline(); return; }
        if (ready) { play(); return; }
        if (engine != null) return; // Share one bounded initialization across requests.
        initialize(null);
    }

    private void initialize(String packageName) {
        selectedEngine = packageName;
        if (packageName != null) attemptedEngines.add(packageName);
        long generation = ++engineGeneration;
        initializationTimeout = () -> { if (generation == engineGeneration && !ready) playOffline(); };
        main.postDelayed(initializationTimeout, 5000);
        try {
            engine = new TextToSpeech(context, status -> main.post(() -> {
                if (generation != engineGeneration) return;
                main.removeCallbacks(initializationTimeout);
                if (status != TextToSpeech.SUCCESS) { playOffline(); return; }
                ready = true;
                if (selectedEngine == null) selectedEngine = engine.getDefaultEngine();
                if (selectedEngine != null) attemptedEngines.add(selectedEngine);
                engine.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { main.post(() -> { if (id.equals(lastChunkId)) finish(false); }); }
                    @Override public void onError(String id) { main.post(() -> { if (belongsToRequest(id)) playOffline(); }); }
                    @Override public void onError(String id, int code) { onError(id); }
                    @Override public void onStop(String id, boolean interrupted) { main.post(() -> { if (belongsToRequest(id)) stop(); }); }
                });
                if (completion != null) play();
            }), packageName);
        } catch (RuntimeException error) { playOffline(); }
    }

    private boolean belongsToRequest(String id) { return !requestId.isEmpty() && id.startsWith(requestId + "."); }

    private void play() {
        if (completion == null) return;
        try {
            int availability = engine.setLanguage(Locale.forLanguageTag(language));
            if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (tryAnotherEngine()) return;
                playOffline(); return;
            }
            if (availability < 0 || engine.setSpeechRate(0.88f) != TextToSpeech.SUCCESS) { playOffline(); return; }
            var chunks = SpeechChunks.split(text, TextToSpeech.getMaxSpeechInputLength());
            lastChunkId = requestId + "." + (chunks.size() - 1);
            playbackTimeout = () -> fail("SPEECH_PLAYBACK_TIMEOUT");
            main.postDelayed(playbackTimeout, Math.min(600000L, Math.max(30000L, text.length() * 350L + 10000L)));
            for (int index = 0; index < chunks.size(); index++) {
                if (engine.speak(chunks.get(index), index == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                    null, requestId + "." + index) != TextToSpeech.SUCCESS) { playOffline(); return; }
            }
        } catch (RuntimeException error) { playOffline(); }
    }

    private void playOffline() {
        if (completion == null) return;
        requestId = UUID.randomUUID().toString(); lastChunkId = "";
        if (playbackTimeout != null) main.removeCallbacks(playbackTimeout);
        disposeEngine();
        playbackTimeout = () -> { offline.stop(); fail("SPEECH_PLAYBACK_TIMEOUT"); };
        main.postDelayed(playbackTimeout, Math.min(600000L, Math.max(30000L, text.length() * 350L + 10000L)));
        offline.speak(text, language, () -> finish(false), this::fail);
    }

    private boolean tryAnotherEngine() {
        for (var installed : engine.getEngines()) {
            if (attemptedEngines.contains(installed.name)) continue;
            String next = installed.name;
            disposeEngine(); initialize(next);
            return true;
        }
        return false;
    }

    void stop() {
        finish(true);
        offline.stop();
        if (engine != null) try { engine.stop(); } catch (RuntimeException ignored) { }
    }

    private Completion takeCompletion() {
        Completion result = completion; completion = null; requestId = ""; lastChunkId = "";
        if (playbackTimeout != null) main.removeCallbacks(playbackTimeout);
        playbackTimeout = null;
        return result;
    }
    private void finish(boolean cancelled) { var result = takeCompletion(); if (result != null) result.complete(cancelled); }
    private void fail(String code) {
        var result = takeCompletion();
        if (engine != null) try { engine.stop(); } catch (RuntimeException ignored) { }
        if (result != null) result.failed(code);
    }
    private void disposeEngine() {
        ++engineGeneration; ready = false;
        if (initializationTimeout != null) main.removeCallbacks(initializationTimeout);
        initializationTimeout = null;
        if (engine != null) try { engine.shutdown(); } catch (RuntimeException ignored) { }
        engine = null;
    }
    void shutdown() { stop(); disposeEngine(); offline.shutdown(); }
}
