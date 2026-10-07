package com.tuyage.client;

import javafx.application.Application;

/**
 * Lanceur explicite de l'application JavaFX Tuyage.
 * Cette classe possède un vrai main(String[]) pour fonctionner aussi bien
 * depuis NetBeans, Maven que depuis les scripts Windows.
 */
public final class ClientLauncher {
    private ClientLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(ClientApp.class, args);
    }
}
