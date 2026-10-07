package com.tuyage.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

final class AudioDeviceUtil {
    static final String AUTO = "Automatique (Windows)";

    private AudioDeviceUtil() {}

    static List<String> inputDevices(AudioFormat[] formats) {
        Set<String> names = new LinkedHashSet<>();
        names.add(AUTO);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (supportsInput(mixer, formats)) names.add(info.getName());
        }
        return new ArrayList<>(names);
    }

    static List<String> outputDevices(AudioFormat[] formats) {
        Set<String> names = new LinkedHashSet<>();
        names.add(AUTO);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (supportsOutput(mixer, formats)) names.add(info.getName());
        }
        return new ArrayList<>(names);
    }

    static TargetDataLine openInput(String preferredName, AudioFormat[] formats) throws Exception {
        Exception last = null;

        if (!isAuto(preferredName)) {
            Mixer mixer = findMixer(preferredName);
            if (mixer != null) {
                try {
                    return openInputOnMixer(mixer, formats);
                } catch (Exception e) {
                    last = e;
                }
            }
        }

        // Windows default/Primary capture first.
        for (AudioFormat format : formats) {
            try {
                TargetDataLine line = AudioSystem.getTargetDataLine(format);
                int frameBytes = frameBytes(format, 20);
                line.open(format, Math.max(frameBytes * 8, 8192));
                return line;
            } catch (Exception e) {
                last = e;
            }
        }

        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                return openInputOnMixer(AudioSystem.getMixer(info), formats);
            } catch (Exception e) {
                last = e;
            }
        }

        throw last == null ? new IllegalStateException("Aucun microphone PCM compatible détecté.") : last;
    }

    static SourceDataLine openOutput(String preferredName, AudioFormat wanted) throws Exception {
        Exception last = null;

        if (!isAuto(preferredName)) {
            Mixer mixer = findMixer(preferredName);
            if (mixer != null) {
                try {
                    return openOutputOnMixer(mixer, wanted);
                } catch (Exception e) {
                    last = e;
                }
            }
        }

        try {
            SourceDataLine line = AudioSystem.getSourceDataLine(wanted);
            int frameBytes = frameBytes(wanted, 20);
            // ~120 ms de tampon matériel: assez pour lisser le Wi-Fi sans effet talkie-walkie.
            line.open(wanted, Math.max(frameBytes * 6, 4096));
            return line;
        } catch (Exception e) {
            last = e;
        }

        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                return openOutputOnMixer(AudioSystem.getMixer(info), wanted);
            } catch (Exception e) {
                last = e;
            }
        }

        throw last == null ? new IllegalStateException("Aucun haut-parleur PCM compatible détecté.") : last;
    }

    static String mixerName(javax.sound.sampled.Line line) {
        if (line == null) return "";
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(info);
                for (var li : mixer.getSourceLineInfo()) {
                    if (li.matches(line.getLineInfo())) return info.getName();
                }
                for (var li : mixer.getTargetLineInfo()) {
                    if (li.matches(line.getLineInfo())) return info.getName();
                }
            } catch (Exception ignored) { }
        }
        return line.getLineInfo().toString();
    }

    static boolean isAuto(String name) {
        return name == null || name.isBlank() || AUTO.equals(name);
    }

    static int frameBytes(AudioFormat format, int millis) {
        int frameSize = Math.max(1, format.getFrameSize());
        int frames = Math.max(1, Math.round(format.getSampleRate() * millis / 1000f));
        return frames * frameSize;
    }

    private static TargetDataLine openInputOnMixer(Mixer mixer, AudioFormat[] formats) throws Exception {
        Exception last = null;
        for (AudioFormat format : formats) {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!mixer.isLineSupported(info)) continue;
            try {
                TargetDataLine line = (TargetDataLine) mixer.getLine(info);
                int frameBytes = frameBytes(format, 20);
                line.open(format, Math.max(frameBytes * 8, 8192));
                return line;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new IllegalArgumentException("Mixer sans entrée compatible") : last;
    }

    private static SourceDataLine openOutputOnMixer(Mixer mixer, AudioFormat wanted) throws Exception {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, wanted);
        if (!mixer.isLineSupported(info)) throw new IllegalArgumentException("Mixer sans sortie compatible");
        SourceDataLine line = (SourceDataLine) mixer.getLine(info);
        int frameBytes = frameBytes(wanted, 20);
        line.open(wanted, Math.max(frameBytes * 6, 4096));
        return line;
    }

    private static Mixer findMixer(String name) {
        if (name == null) return null;
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (name.equals(info.getName())) return AudioSystem.getMixer(info);
        }
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().toLowerCase().contains(name.toLowerCase())) return AudioSystem.getMixer(info);
        }
        return null;
    }

    private static boolean supportsInput(Mixer mixer, AudioFormat[] formats) {
        for (AudioFormat format : formats) {
            try {
                if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, format))) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static boolean supportsOutput(Mixer mixer, AudioFormat[] formats) {
        for (AudioFormat format : formats) {
            try {
                if (mixer.isLineSupported(new DataLine.Info(SourceDataLine.class, format))) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }
}
