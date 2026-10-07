package com.tuyage.client;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

final class AudioDeviceDialog {
    record Selection(String input, String output) {}

    private AudioDeviceDialog() {}

    static Selection show(Window owner, AudioFormat[] formats, String currentInput, String currentOutput) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Tuyage • Périphériques audio");
        dialog.setHeaderText("Choisir le microphone et le haut-parleur de l'appel");

        List<String> inputs = AudioDeviceUtil.inputDevices(formats);
        List<String> outputs = AudioDeviceUtil.outputDevices(formats);

        ComboBox<String> input = new ComboBox<>(FXCollections.observableArrayList(inputs));
        ComboBox<String> output = new ComboBox<>(FXCollections.observableArrayList(outputs));
        input.setMaxWidth(Double.MAX_VALUE);
        output.setMaxWidth(Double.MAX_VALUE);
        input.setValue(inputs.contains(currentInput) ? currentInput : AudioDeviceUtil.AUTO);
        output.setValue(outputs.contains(currentOutput) ? currentOutput : AudioDeviceUtil.AUTO);

        Label micResult = new Label("Parle pendant le test: un niveau supérieur à ~300 indique que la voix est bien captée.");
        micResult.setWrapText(true);
        micResult.getStyleClass().add("muted");

        Button testMic = new Button("🎙 Tester le micro (2 s)");
        Button testSpeaker = new Button("🔊 Tester le haut-parleur");

        testMic.setOnAction(e -> {
            testMic.setDisable(true);
            micResult.setText("Test du microphone… parle maintenant.");
            String selected = input.getValue();
            Thread t = new Thread(() -> {
                double max = 0;
                String detail = "";
                TargetDataLine line = null;
                try {
                    line = AudioDeviceUtil.openInput(selected, formats);
                    line.start();
                    detail = line.getFormat().getSampleRate() + " Hz / " + line.getFormat().getChannels() + " ch";
                    int bytes = Math.max(960, AudioDeviceUtil.frameBytes(line.getFormat(), 20));
                    byte[] buffer = new byte[bytes];
                    long until = System.currentTimeMillis() + 2000;
                    while (System.currentTimeMillis() < until) {
                        int n = line.read(buffer, 0, buffer.length);
                        if (n > 1) max = Math.max(max, rms(buffer, n));
                    }
                    double result = max;
                    String format = detail;
                    Platform.runLater(() -> micResult.setText(
                            "Niveau max: " + Math.round(result) + " • " + format +
                                    (result < 100 ? " • très faible: choisis un autre microphone ou vérifie Windows." : " • microphone OK.")));
                } catch (Exception ex) {
                    Platform.runLater(() -> micResult.setText("Échec micro: " + safe(ex)));
                } finally {
                    if (line != null) {
                        try { line.stop(); } catch (Exception ignored) { }
                        try { line.close(); } catch (Exception ignored) { }
                    }
                    Platform.runLater(() -> testMic.setDisable(false));
                }
            }, "tuyage-audio-device-test-mic");
            t.setDaemon(true);
            t.start();
        });

        testSpeaker.setOnAction(e -> {
            String selected = output.getValue();
            Thread t = new Thread(() -> {
                SourceDataLine line = null;
                try {
                    AudioFormat format = new AudioFormat(48000f, 16, 1, true, false);
                    line = AudioDeviceUtil.openOutput(selected, format);
                    line.start();
                    byte[] tone = tone(format, 660.0, 420);
                    line.write(tone, 0, tone.length);
                    line.drain();
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        Alert a = new Alert(Alert.AlertType.ERROR);
                        if (owner != null) a.initOwner(owner);
                        a.setTitle("Tuyage");
                        a.setHeaderText("Test haut-parleur impossible");
                        a.setContentText(safe(ex));
                        a.showAndWait();
                    });
                } finally {
                    if (line != null) {
                        try { line.stop(); } catch (Exception ignored) { }
                        try { line.close(); } catch (Exception ignored) { }
                    }
                }
            }, "tuyage-audio-device-test-speaker");
            t.setDaemon(true);
            t.start();
        });

        VBox body = new VBox(10,
                new Label("Microphone"), input,
                testMic, micResult,
                new Label("Haut-parleur / casque"), output,
                testSpeaker,
                new Label("Conseil TP: sur deux PC, choisis le micro et le haut-parleur/casque réels de chaque machine plutôt qu'un périphérique virtuel."));
        body.setPadding(new Insets(12));
        body.setPrefWidth(560);
        body.getChildren().get(body.getChildren().size()-1).getStyleClass().add("muted");

        dialog.getDialogPane().setContent(body);
        ButtonType apply = new ButtonType("Appliquer", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);

        return dialog.showAndWait()
                .filter(bt -> bt == apply)
                .map(bt -> new Selection(input.getValue(), output.getValue()))
                .orElse(null);
    }

    private static double rms(byte[] pcm, int length) {
        if (pcm == null || length < 2) return 0;
        double sum = 0;
        int count = 0;
        int limit = Math.min(length, pcm.length);
        for (int i = 0; i + 1 < limit; i += 2) {
            short sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (double) sample * sample;
            count++;
        }
        return count == 0 ? 0 : Math.sqrt(sum / count);
    }

    private static byte[] tone(AudioFormat format, double frequency, int millis) {
        int frames = Math.max(1, Math.round(format.getSampleRate() * millis / 1000f));
        int frameSize = Math.max(2, format.getFrameSize());
        byte[] data = new byte[frames * frameSize];
        for (int i = 0; i < frames; i++) {
            short sample = (short) (Math.sin(2 * Math.PI * frequency * i / format.getSampleRate()) * 9000);
            for (int ch = 0; ch < Math.max(1, format.getChannels()); ch++) {
                int pos = i * frameSize + ch * 2;
                if (pos + 1 < data.length) {
                    data[pos] = (byte) (sample & 0xff);
                    data[pos + 1] = (byte) ((sample >>> 8) & 0xff);
                }
            }
        }
        return data;
    }

    private static String safe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
