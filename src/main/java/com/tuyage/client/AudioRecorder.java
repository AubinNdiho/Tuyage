package com.tuyage.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

final class AudioRecorder {
    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    private TargetDataLine line;
    private ByteArrayOutputStream raw;
    private Thread worker;
    private final AtomicBoolean recording = new AtomicBoolean(false);

    boolean isRecording(){ return recording.get(); }

    void start() throws Exception {
        if (isRecording()) return;
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) throw new IllegalStateException("Aucun microphone compatible n'est disponible.");
        line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(FORMAT);
        line.start();
        raw = new ByteArrayOutputStream();
        recording.set(true);
        worker = new Thread(() -> {
            byte[] buffer = new byte[2048];
            try {
                while (recording.get()) {
                    int n = line.read(buffer, 0, buffer.length);
                    if (n > 0) raw.write(buffer, 0, n);
                }
            } catch (Exception ignored) {
            }
        }, "tuyage-audio-recorder");
        worker.setDaemon(true);
        worker.start();
    }

    byte[] stop() throws Exception {
        if (!isRecording()) return new byte[0];
        recording.set(false);
        if (line != null) {
            try { line.stop(); } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
        }
        if (worker != null) worker.join(1200);
        byte[] pcm = raw == null ? new byte[0] : raw.toByteArray();
        line = null; worker = null; raw = null;
        if (pcm.length == 0) return new byte[0];
        try (ByteArrayInputStream in = new ByteArrayInputStream(pcm);
             AudioInputStream audio = new AudioInputStream(in, FORMAT, pcm.length / FORMAT.getFrameSize());
             ByteArrayOutputStream wav = new ByteArrayOutputStream()) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, wav);
            return wav.toByteArray();
        }
    }

    void cancel() {
        recording.set(false);
        if (line != null) {
            try { line.stop(); } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
        }
        line = null; worker = null; raw = null;
    }
}
