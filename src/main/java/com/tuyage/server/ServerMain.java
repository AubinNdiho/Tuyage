package com.tuyage.server;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class ServerMain {
    private Server server;

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("tuyage.port", 9090);
        new ServerMain().start(port);
    }

    private void start(int port) throws IOException, InterruptedException {
        server = NettyServerBuilder.forPort(port)
                .maxInboundMessageSize(25 * 1024 * 1024)
                .addService(new ChatServiceImpl())
                .build()
                .start();

        System.out.println("========================================================");
        System.out.println("  Tuyage gRPC Messenger - SERVEUR DEMARRE");
        System.out.println("  Port : " + port);
        System.out.println("  Base : ./data/tuyage.mv.db");
        System.out.println("  Ctrl+C pour arreter");
        System.out.println("========================================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { stop(); } catch (InterruptedException ignored) {}
        }));
        server.awaitTermination();
    }

    private void stop() throws InterruptedException {
        if (server != null) server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
}
