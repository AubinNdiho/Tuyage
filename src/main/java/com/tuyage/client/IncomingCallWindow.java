package com.tuyage.client;

import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * Fenêtre d'appel entrant dédiée, plus proche d'un vrai écran de téléphone.
 * Elle reste indépendante du chat et permet de décrocher/refuser immédiatement.
 */
final class IncomingCallWindow {
    private final Stage stage = new Stage(StageStyle.DECORATED);
    private boolean decided;

    IncomingCallWindow(ClientApp app,
                       String callerName,
                       String groupName,
                       boolean video,
                       boolean groupCall,
                       Runnable acceptAction,
                       Runnable rejectAction) {
        stage.initOwner(app.stage());
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(true);
        stage.setTitle("Tuyage • Appel entrant");
        stage.setMinWidth(420);
        stage.setMinHeight(570);

        Label brand = new Label("TUYAGE");
        brand.setStyle("-fx-font-size: 13px; -fx-font-weight: 800; -fx-text-fill: rgba(255,255,255,.72); -fx-letter-spacing: 2px;");

        Label type = new Label(video ? "🎥  Appel vidéo entrant" : "📞  Appel audio entrant");
        type.setStyle("-fx-font-size: 15px; -fx-text-fill: rgba(255,255,255,.82); -fx-font-weight: 700;");

        String display = groupCall && groupName != null && !groupName.isBlank() ? groupName : callerName;
        Label avatar = new Label(initials(display));
        avatar.setMinSize(132, 132);
        avatar.setMaxSize(132, 132);
        avatar.setAlignment(Pos.CENTER);
        avatar.setStyle("-fx-background-color: linear-gradient(to bottom right,#37d9b3,#7272ff);"
                + "-fx-background-radius: 70; -fx-text-fill:white; -fx-font-size:42px; -fx-font-weight:900;"
                + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,.35),28,.15,0,9);");

        ScaleTransition pulse = new ScaleTransition(Duration.millis(900), avatar);
        pulse.setFromX(1.0); pulse.setFromY(1.0);
        pulse.setToX(1.06); pulse.setToY(1.06);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(ScaleTransition.INDEFINITE);
        pulse.play();

        Label name = new Label(display == null || display.isBlank() ? "Contact Tuyage" : display);
        name.setWrapText(true);
        name.setAlignment(Pos.CENTER);
        name.setStyle("-fx-font-size:28px; -fx-font-weight:900; -fx-text-fill:white;");

        Label detail = new Label(groupCall
                ? ((callerName == null ? "Un membre" : callerName) + " lance un appel dans ce groupe")
                : "Tuyage vous appelle…");
        detail.setWrapText(true);
        detail.setAlignment(Pos.CENTER);
        detail.setStyle("-fx-font-size:14px; -fx-text-fill:rgba(255,255,255,.72);");

        Button reject = new Button(groupCall ? "✕  Ignorer" : "✕  Refuser");
        reject.setMinSize(145, 58);
        reject.setStyle("-fx-background-color:#ef4d61; -fx-background-radius:30; -fx-text-fill:white; -fx-font-size:16px; -fx-font-weight:800; -fx-cursor:hand;");
        Button accept = new Button(groupCall ? "✓  Rejoindre" : "☎  Décrocher");
        accept.setMinSize(155, 58);
        accept.setStyle("-fx-background-color:#20c997; -fx-background-radius:30; -fx-text-fill:white; -fx-font-size:16px; -fx-font-weight:800; -fx-cursor:hand;");

        Runnable doReject = () -> {
            if (decided) return;
            decided = true;
            pulse.stop();
            stage.close();
            rejectAction.run();
        };
        Runnable doAccept = () -> {
            if (decided) return;
            decided = true;
            pulse.stop();
            // On crée/affiche d'abord la vraie fenêtre d'appel connecté, puis
            // seulement on retire l'écran d'appel entrant. Cela évite le
            // clignotement/disparition observé au clic sur « Décrocher ».
            acceptAction.run();
            stage.hide();
        };
        reject.setOnAction(e -> doReject.run());
        accept.setOnAction(e -> doAccept.run());

        HBox actions = new HBox(26, reject, accept);
        actions.setAlignment(Pos.CENTER);

        VBox card = new VBox(18, brand, type, avatar, name, detail, actions);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(28, 28, 34, 28));
        card.setMaxWidth(390);
        card.setStyle("-fx-background-color:linear-gradient(to bottom,#11162c,#20294b); -fx-background-radius:32;"
                + "-fx-border-color:rgba(255,255,255,.10); -fx-border-radius:32; -fx-border-width:1;"
                + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,.35),35,.12,0,12);");

        StackPane root = new StackPane(card);
        root.setPadding(new Insets(18));
        root.setStyle("-fx-background-color:linear-gradient(to bottom right,#080b18,#151b35);");
        Scene scene = new Scene(root, 460, 620);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) doAccept.run();
            else if (e.getCode() == KeyCode.ESCAPE) doReject.run();
        });
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (!decided) {
                e.consume();
                doReject.run();
            }
        });
    }

    void show() {
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    void closeSilently() {
        decided = true;
        stage.close();
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "T";
        String[] p = name.trim().split("\\s+");
        if (p.length == 1) return p[0].substring(0, Math.min(2, p[0].length())).toUpperCase();
        return (p[0].substring(0,1) + p[p.length-1].substring(0,1)).toUpperCase();
    }
}
