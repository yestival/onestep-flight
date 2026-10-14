package com.selene.onestepflight;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

/** Bundled, independent UTF-8-to-WAV synthesizer. No system engine or network. */
final class OfflineSpeech {
    private static final Object DATA_LOCK = new Object();
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong generation = new AtomicLong();
    private volatile Process process;
    private Future<?> work;
    private MediaPlayer player;
    private File playingFile;

    OfflineSpeech(Context context) { this.context = context.getApplicationContext(); }

    void speak(String text, String language, Runnable completed, Consumer<String> failed) {
        stop(); long token = generation.get();
        work = worker.submit(() -> {
            File wave = null;
            try {
                File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libflight_espeak.so");
                if (!executable.canExecute()) throw new IOException("Bundled speech executable unavailable");
                File data = prepareData();
                if (generation.get() != token) return;
                wave = File.createTempFile("flight-voice-", ".wav", context.getCacheDir());
                String voice = language.toLowerCase(Locale.ROOT).startsWith("zh") ? "cmn"
                    : language.toLowerCase(Locale.ROOT).startsWith("es") ? "es" : "en-us";
                Process running = new ProcessBuilder(executable.getAbsolutePath(), "--path=" + data.getAbsolutePath(),
                    "-v", voice, "-s", "155", "-b", "1", "-w", wave.getAbsolutePath(), "--stdin")
                    .redirectErrorStream(true).start();
                process = running;
                if (generation.get() != token) { running.destroy(); return; }
                try (OutputStream input = running.getOutputStream()) { input.write(text.getBytes(StandardCharsets.UTF_8)); }
                // Drain diagnostics without retaining or logging spoken text.
                try (InputStream output = running.getInputStream()) { byte[] bytes = new byte[2048]; while (output.read(bytes) != -1) { if (generation.get() != token) break; } }
                if (running.waitFor() != 0 || wave.length() <= 44) throw new IOException("Bundled synthesis failed");
                if (process == running) process = null;
                File audio = wave; wave = null;
                main.post(() -> {
                    if (generation.get() != token) { audio.delete(); return; }
                    startPlayer(audio, token, completed, failed);
                });
            } catch (Exception error) {
                main.post(() -> { if (generation.get() == token) { stop(); failed.accept("SPEECH_OFFLINE_ENGINE_FAILED"); } });
            } finally { if (wave != null) wave.delete(); }
        });
    }

    private void startPlayer(File audio, long token, Runnable completed, Consumer<String> failed) {
        try {
            playingFile = audio; player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(audio.getAbsolutePath());
            player.setOnPreparedListener(ready -> { if (generation.get() == token) ready.start(); });
            player.setOnCompletionListener(done -> { if (generation.get() == token) { releasePlayer(); completed.run(); } });
            player.setOnErrorListener((broken, what, extra) -> { if (generation.get() == token) { releasePlayer(); failed.accept("SPEECH_PLAYBACK_FAILED"); } return true; });
            player.prepareAsync();
        } catch (Exception error) { releasePlayer(); failed.accept("SPEECH_PLAYBACK_FAILED"); }
    }

    private File prepareData() throws IOException {
        synchronized (DATA_LOCK) {
            String version;
            try (InputStream stream = context.getAssets().open("offline-speech/version")) {
                byte[] bytes = new byte[128]; int count = stream.read(bytes);
                version = count > 0 ? new String(bytes, 0, count, StandardCharsets.US_ASCII).trim() : "";
            }
            if (!version.matches("[a-f0-9]{64}")) throw new IOException("Invalid bundled voice version");
            File root = new File(context.getFilesDir(), "offline-speech");
            File directory = new File(root, version);
            if (dataReady(directory)) return directory;
            File staging = new File(root, "extract-" + UUID.randomUUID());
            if (!staging.mkdirs()) throw new IOException("Unable to prepare offline voices");
            try {
                String boundary = staging.getCanonicalPath() + File.separator;
                long total = 0; int entries = 0;
                try (ZipInputStream zip = new ZipInputStream(context.getAssets().open("offline-speech/espeak-ng-data.zip"))) {
                    for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                        if (++entries > 4096) throw new IOException("Too many voice entries");
                        File target = new File(staging, entry.getName());
                        if (!target.getCanonicalPath().startsWith(boundary)) throw new IOException("Invalid voice entry");
                        if (entry.isDirectory()) { if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Unable to create voice directory"); continue; }
                        File parent = target.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Unable to create voice directory");
                        try (OutputStream output = new FileOutputStream(target)) {
                            byte[] buffer = new byte[8192]; int count;
                            while ((count = zip.read(buffer)) != -1) {
                                total += count; if (total > 128L * 1024 * 1024 || Thread.currentThread().isInterrupted()) throw new IOException("Voice extraction interrupted");
                                output.write(buffer, 0, count);
                            }
                        }
                    }
                }
                if (!dataReady(staging)) throw new IOException("Bundled voices incomplete");
                if (directory.exists() && !dataReady(directory)) removeTree(directory);
                if (!staging.renameTo(directory) && !dataReady(directory)) throw new IOException("Unable to install bundled voices");
                return directory;
            } finally { removeTree(staging); }
        }
    }
    private boolean dataReady(File root) {
        File data = new File(root, "espeak-ng-data");
        for (String name : new String[] {"phondata", "phonindex", "phontab", "intonations", "en_dict", "cmn_dict", "es_dict"}) {
            if (!new File(data, name).isFile()) return false;
        }
        return true;
    }
    private void removeTree(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) removeTree(child);
        if (file.exists()) file.delete();
    }
    private void releasePlayer() {
        if (player != null) { player.release(); player = null; }
        if (playingFile != null) { playingFile.delete(); playingFile = null; }
    }
    void stop() {
        generation.incrementAndGet();
        if (work != null) { work.cancel(true); work = null; }
        Process running = process; process = null; if (running != null) running.destroy();
        releasePlayer();
    }
    void shutdown() { stop(); worker.shutdownNow(); }
}
