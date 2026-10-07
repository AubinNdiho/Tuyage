package com.tuyage.client;

import com.tuyage.server.ServerMain;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Lanceur Windows "poste hote" : demarre le serveur local si le port 9090
 * est libre, puis ouvre le client JavaFX. Pour un poste distant, utiliser
 * Tuyage-Client afin de ne pas creer un second serveur local.
 */
public final class TuyageDesktopLauncher {
    private TuyageDesktopLauncher() {}

    public static void main(String[] args) {
        if (!isLocalServerListening(9090)) {
            Thread serverThread = new Thread(() -> {
                try {
                    ServerMain.main(new String[0]);
                } catch (Exception e) {
                    System.err.println("[Tuyage] Serveur local non demarre: " + e.getMessage());
                }
            }, "tuyage-local-server");
            serverThread.setDaemon(true);
            serverThread.start();
            waitForLocalServer(9090, 6000);
        }
        ClientLauncher.main(args);
    }

    private static boolean isLocalServerListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void waitForLocalServer(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isLocalServerListening(port)) return;
            try { Thread.sleep(150); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
}
