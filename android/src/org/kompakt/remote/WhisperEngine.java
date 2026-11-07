package org.kompakt.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Shared offline engine. Audio stays in memory; the model unloads after idle time. */
public final class WhisperEngine {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String MODEL = "ggml-tiny.en-q5_1.bin";
    private static final String SHA = "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b";
    private static volatile boolean loaded;
    public interface Callback { void complete(String text, long milliseconds, String error); }
    public static final class Job {
        final int id = SEQUENCE.incrementAndGet();
        final AtomicBoolean cancelled = new AtomicBoolean();
        public void cancel() { cancelled.set(true); if (loaded) nativeCancel(id); }
    }
    private static native byte[] nativeTranscribe(String model, short[] audio, int id) throws IOException;
    private static native void nativeCancel(int id);
    private static native void nativeUnload();
    public static Job transcribe(Context context, short[] audio, Callback callback) {
        Job job = new Job();
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            long start = android.os.SystemClock.elapsedRealtime();
            String text = "", error = null;
            try {
                if (!job.cancelled.get() && hasSpeechEnergy(audio)) {
                    if (!loaded) { System.loadLibrary("kompakt_speech"); loaded = true; }
                    File model = modelFile(app);
                    if (!job.cancelled.get()) {
                        byte[] result = nativeTranscribe(model.getAbsolutePath(), audio, job.id);
                        if (result != null) text = new String(result, StandardCharsets.UTF_8).trim();
                    }
                }
            } catch (Exception | LinkageError e) {
                error = e.getMessage() == null ? "Speech recognition could not start." : e.getMessage();
            } finally { java.util.Arrays.fill(audio, (short)0); }
            String value = text, problem = error;
            long elapsed = android.os.SystemClock.elapsedRealtime() - start;
            MAIN.post(() -> { if (!job.cancelled.get()) callback.complete(value, elapsed, problem); });
            MAIN.postDelayed(() -> WORKER.execute(() -> {
                if (loaded && SEQUENCE.get() == job.id) nativeUnload();
            }), 60000);
        });
        return job;
    }
    static boolean hasSpeechEnergy(short[] audio) {
        if (audio.length < 1600) return false;
        double sum = 0; int peak = 0;
        for (short s : audio) { sum += (double)s*s; peak = Math.max(peak, Math.abs((int)s)); }
        return peak >= 300 && Math.sqrt(sum/audio.length) >= 80;
    }
    private static File modelFile(Context context) throws Exception {
        File target = new File(context.getFilesDir(), MODEL);
        if (target.isFile() && target.length() == 32166155) return target;
        File temp = new File(context.getFilesDir(), MODEL+".tmp");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = context.getAssets().open(MODEL); OutputStream out = new FileOutputStream(temp)) {
            byte[] block = new byte[65536]; int n;
            while ((n=in.read(block)) != -1) { out.write(block,0,n); digest.update(block,0,n); }
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.US,"%02x",b & 255));
        if (!SHA.equals(hash.toString())) { temp.delete(); throw new IOException("Speech model checksum failed."); }
        if (!temp.renameTo(target)) throw new IOException("Could not prepare the speech model.");
        return target;
    }
}
