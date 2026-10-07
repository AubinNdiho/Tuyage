package com.tuyage.client;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

final class CallWindow {
    private final Stage stage = new Stage();
    private final Label status = new Label("Connexion...");
    private final Label duration = new Label("00:00");
    private final Label warning = new Label();
    private final ImageView remoteVideo = new ImageView();
    private final ImageView localVideo = new ImageView();
    private final Label remotePlaceholder = new Label("En attente de la vidéo du correspondant…");
    private final Label remoteSource = new Label();
    private final Button mute = new Button("🎙 Micro");
    private final Button camera = new Button("📷 Caméra");
    private final Button audio = new Button("⚙ Audio");
    private final Button end = new Button("📞 Raccrocher");
    private final Runnable endAction;
    private final Runnable muteAction;
    private final Runnable cameraAction;
    private final Runnable audioAction;
    private Timeline timer;
    private Instant connectedAt;
    private boolean closing;

    CallWindow(ClientApp app, String peerName, boolean video, Runnable endAction, Runnable muteAction, Runnable cameraAction, Runnable audioAction) {
        this.endAction = endAction;
        this.muteAction = muteAction;
        this.cameraAction = cameraAction;
        this.audioAction = audioAction;

        stage.initOwner(app.stage());
        stage.initModality(Modality.NONE);
        stage.setTitle((video ? "Appel vidéo" : "Appel audio") + " • Tuyage");
        try { stage.getIcons().add(new Image(getClass().getResourceAsStream("/images/taskbar-icon.png"))); } catch (Exception ignored) {}
        stage.setMinWidth(video ? 860 : 430);
        stage.setMinHeight(video ? 600 : 420);

        Label avatar = new Label(initials(peerName));
        avatar.getStyleClass().add("call-avatar");
        Label name = new Label(peerName);
        name.getStyleClass().add("call-name");
        status.getStyleClass().add("call-status");
        duration.getStyleClass().add("call-duration");
        warning.getStyleClass().add("call-warning");
        warning.setWrapText(true);
        warning.setVisible(false);
        warning.setManaged(false);

        VBox identity = new VBox(8, avatar, name, status, duration, warning);
        identity.setAlignment(Pos.CENTER);
        identity.setPadding(new Insets(video ? 12 : 22));

        mute.getStyleClass().add("call-control");
        camera.getStyleClass().add("call-control");
        audio.getStyleClass().add("call-control");
        end.getStyleClass().add("call-end");
        end.setText("📞 Raccrocher");
        end.setMinWidth(150);
        mute.setOnAction(e -> muteAction.run());
        camera.setOnAction(e -> cameraAction.run());
        audio.setOnAction(e -> audioAction.run());
        end.setOnAction(e -> endAction.run());
        HBox controls = new HBox(12, mute, camera, audio, end);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(14, 18, 14, 18));
        controls.setMaxWidth(520);
        controls.setStyle("-fx-background-color: rgba(20,24,48,0.90); -fx-background-radius: 24;");

        BorderPane root = new BorderPane();
        root.getStyleClass().add("call-root");

        if (video) {
            remoteVideo.setFitWidth(840);
            remoteVideo.setFitHeight(470);
            remoteVideo.setPreserveRatio(true);
            localVideo.setFitWidth(200);
            localVideo.setFitHeight(140);
            localVideo.setPreserveRatio(true);
            remoteVideo.getStyleClass().add("remote-video");
            localVideo.getStyleClass().add("local-video");
            remotePlaceholder.getStyleClass().add("remote-video-placeholder");
            remotePlaceholder.setWrapText(true);
            remoteSource.getStyleClass().add("remote-video-source");
            remoteSource.setVisible(false);
            remoteSource.setManaged(false);

            StackPane remoteLayer = new StackPane(remotePlaceholder, remoteVideo, remoteSource);
            StackPane.setAlignment(remoteSource, Pos.BOTTOM_LEFT);
            StackPane.setMargin(remoteSource, new Insets(0, 0, 82, 18));
            Label localSource = new Label("Vous");
            localSource.getStyleClass().add("local-video-source");
            StackPane localLayer = new StackPane(localVideo, localSource);
            localLayer.setMaxSize(206, 146);
            StackPane.setAlignment(localSource, Pos.BOTTOM_LEFT);
            StackPane.setMargin(localSource, new Insets(0, 0, 6, 6));
            StackPane videoPane = new StackPane(remoteLayer, localLayer, controls);
            // Comme WhatsApp : le correspondant occupe la grande zone et "Vous" reste en vignette.
            StackPane.setAlignment(localLayer, Pos.TOP_RIGHT);
            StackPane.setMargin(localLayer, new Insets(14, 14, 0, 0));
            StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
            StackPane.setMargin(controls, new Insets(0, 0, 18, 0));
            videoPane.getStyleClass().add("video-pane");

            root.setTop(identity);
            root.setCenter(videoPane);
        } else {
            root.setCenter(identity);
            camera.setVisible(false);
            camera.setManaged(false);
            controls.setStyle("-fx-background-color: transparent;");
            root.setBottom(controls);
        }

        Scene scene = new Scene(root, video ? 920 : 460, video ? 680 : 450);
        app.applyTheme(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && !closing) endAction.run();
        });
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (!closing) {
                e.consume();
                endAction.run();
            }
        });
    }

    void showRinging(boolean outgoing, boolean video) {
        status.setText(outgoing
                ? (video ? "Appel vidéo… en attente de réponse" : "Appel audio… en attente de réponse")
                : "Connexion de l'appel...");
        end.setText("📞 Raccrocher");
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    void connected() {
        status.setText("Connecté");
        end.setText("📞 Raccrocher");
        ensureVisible();
        connectedAt = Instant.now();
        if (timer != null) timer.stop();
        timer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> updateDuration()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }


    void ensureVisible() {
        Platform.runLater(() -> {
            if (closing) return;
            if (!stage.isShowing()) stage.show();
            stage.setIconified(false);
            stage.toFront();
            stage.requestFocus();
        });
    }

    void setStatus(String text) {
        Platform.runLater(() -> status.setText(text));
    }

    void setWarning(String text) {
        Platform.runLater(() -> {
            warning.setText(text == null ? "" : text);
            boolean show = text != null && !text.isBlank();
            warning.setVisible(show);
            warning.setManaged(show);
        });
    }

    void setMuted(boolean value) {
        Platform.runLater(() -> mute.setText(value ? "🔇 Muet" : "🎙 Micro"));
    }

    void setCameraEnabled(boolean value) {
        Platform.runLater(() -> camera.setText(value ? "📷 Caméra" : "🚫 Caméra"));
    }

    void updateRemoteFrame(byte[] jpeg) { updateRemoteFrame(jpeg, ""); }

    void updateRemoteFrame(byte[] jpeg, String sourceName) {
        if (jpeg == null || jpeg.length == 0) return;
        Platform.runLater(() -> {
            try {
                remoteVideo.setImage(new Image(new ByteArrayInputStream(jpeg)));
                remotePlaceholder.setVisible(false);
                remotePlaceholder.setManaged(false);
                if (sourceName != null && !sourceName.isBlank()) {
                    remoteSource.setText(sourceName);
                    remoteSource.setVisible(true);
                    remoteSource.setManaged(true);
                }
            } catch (Exception ignored) {}
        });
    }

    void updateLocalFrame(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;
        Platform.runLater(() -> {
            try { localVideo.setImage(new Image(new ByteArrayInputStream(jpeg))); } catch (Exception ignored) {}
        });
    }

    void close(String finalStatus) {
        Platform.runLater(() -> {
            if (closing) return;
            closing = true;
            if (timer != null) timer.stop();
            if (finalStatus != null && !finalStatus.isBlank()) status.setText(finalStatus);
            stage.close();
        });
    }

    private void updateDuration() {
        if (connectedAt == null) return;
        long seconds = Duration.between(connectedAt, Instant.now()).getSeconds();
        duration.setText(String.format("%02d:%02d", seconds / 60, seconds % 60));
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        return (parts[0].substring(0,1) + parts[parts.length-1].substring(0,1)).toUpperCase();
    }
}
