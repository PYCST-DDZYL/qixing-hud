package com.qixing.hud.glasses;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Plays voice prompts the phone synthesized (16-bit mono PCM) on the glasses' speaker. */
final class VoicePlayer {
    private static final String TAG = "QixingHud";
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger gen = new java.util.concurrent.atomic.AtomicInteger();
    private AudioTrack current; // player thread only

    /** Newest prompt wins: anything still playing is cut off, like the phone's QUEUE_FLUSH. */
    void play(String line) {
        final int g = gen.incrementAndGet();
        ex.execute(() -> {
            if (g != gen.get()) return; // a newer prompt is already waiting
            try {
                JSONObject j = new JSONObject(line);
                int rate = j.optInt("rate", 0);
                byte[] pcm = Base64.decode(j.optString("pcm", ""), Base64.DEFAULT);
                if (rate < 4000 || rate > 48000 || pcm.length < 2 || pcm.length > 4 * 1024 * 1024) return;
                int len = pcm.length & ~1;
                stop();
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(len)
                        .build();
                t.write(pcm, 0, len);
                t.play();
                current = t;
                // Release once it has finished (static tracks do not stop by themselves).
                long ms = len / 2 * 1000L / rate + 300;
                long until = System.currentTimeMillis() + ms;
                while (g == gen.get() && System.currentTimeMillis() < until) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (ex.isShutdown()) break;
                }
                stop();
            } catch (Throwable e) {
                Log.w(TAG, "voice: " + e);
                stop();
            }
        });
    }

    private void stop() {
        AudioTrack t = current;
        current = null;
        if (t == null) return;
        try {
            t.stop();
        } catch (Exception ignored) {
        }
        t.release();
    }

    void shutdown() {
        ex.shutdownNow();
    }
}
