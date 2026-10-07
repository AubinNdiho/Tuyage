package com.tuyage.client;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;

final class MediaViewer {
    private MediaViewer() {}

    static void open(String fileName, String mimeType, byte[] data, boolean video) {
        if (!video && ("audio/wav".equalsIgnoreCase(mimeType) || (fileName != null && fileName.toLowerCase().endsWith(".wav")))) {
            openWav(fileName, data);
            return;
        }
        openJavaFxMedia(fileName, data, video);
    }

    static void openImage(String fileName, byte[] data) {
        try {
            ImageView image = new ImageView(new Image(new ByteArrayInputStream(data)));
            image.setFitWidth(900);
            image.setFitHeight(650);
            image.setPreserveRatio(true);
            BorderPane root = new BorderPane(image);
            root.setPadding(new Insets(16));
            Stage stage = new Stage();
            stage.setTitle("Tuyage • " + (fileName == null || fileName.isBlank() ? "Image" : fileName));
            stage.setScene(new Scene(root, 940, 700));
            stage.show();
        } catch (Exception e) {
            throw new RuntimeException("Image impossible à ouvrir : " + e.getMessage(), e);
        }
    }

    private static void openWav(String fileName, byte[] data) {
        try {
            AudioInputStream input = AudioSystem.getAudioInputStream(new ByteArrayInputStream(data));
            Clip clip = AudioSystem.getClip();
            clip.open(input);

            Label state = new Label("Audio prêt");
            Button play = new Button("▶ Lecture");
            Button restart = new Button("↺ Recommencer");
            play.setOnAction(e -> {
                if (clip.isRunning()) {
                    clip.stop();
                    play.setText("▶ Lecture");
                    state.setText("En pause");
                } else {
                    if (clip.getFramePosition() >= clip.getFrameLength()) clip.setFramePosition(0);
                    clip.start();
                    play.setText("⏸ Pause");
                    state.setText("Lecture en cours");
                }
            });
            restart.setOnAction(e -> {
                clip.stop();
                clip.setFramePosition(0);
                clip.start();
                play.setText("⏸ Pause");
                state.setText("Lecture en cours");
            });

            HBox controls = new HBox(10, play, restart);
            controls.setAlignment(Pos.CENTER);
            BorderPane root = new BorderPane();
            root.setPadding(new Insets(18));
            root.setTop(state);
            BorderPane.setAlignment(state, Pos.CENTER);
            root.setCenter(controls);

            Stage stage = new Stage();
            stage.setTitle("Tuyage • " + (fileName == null || fileName.isBlank() ? "Audio" : fileName));
            stage.setScene(new Scene(root, 430, 150));
            stage.setOnHidden(e -> {
                try { clip.stop(); clip.close(); input.close(); } catch (Exception ignored) {}
            });
            stage.show();
        } catch (Exception e) {
            throw new RuntimeException("Lecture audio impossible : " + e.getMessage(), e);
        }
    }

    private static void openJavaFxMedia(String fileName, byte[] data, boolean video) {
        try {
            String suffix = fileName != null && fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : (video ? ".mp4" : ".mp3");
            Path tmp = Files.createTempFile("tuyage-media-", suffix);
            Files.write(tmp, data);
            tmp.toFile().deleteOnExit();

            MediaPlayer player = new MediaPlayer(new Media(tmp.toUri().toString()));
            BorderPane root = new BorderPane();
            root.setPadding(new Insets(14));
            if (video) {
                MediaView view = new MediaView(player);
                view.setPreserveRatio(true);
                view.setFitWidth(760);
                view.setFitHeight(460);
                root.setCenter(view);
            }
            Label status = new Label("Prêt");
            Button play = new Button("▶ Lecture / Pause");
            play.setOnAction(e -> {
                if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                    player.pause();
                    status.setText("En pause");
                } else {
                    player.play();
                    status.setText("Lecture en cours");
                }
            });
            HBox controls = new HBox(10, play, status);
            controls.setAlignment(Pos.CENTER);
            root.setBottom(controls);

            Stage stage = new Stage();
            stage.setTitle("Tuyage • " + fileName);
            stage.setScene(new Scene(root, video ? 800 : 480, video ? 560 : 150));
            stage.setOnHidden(e -> player.dispose());
            stage.show();
            player.play();
            status.setText("Lecture en cours");
        } catch (Exception e) {
            throw new RuntimeException("Lecture média impossible : " + e.getMessage(), e);
        }
    }
}
