package com.tuyage.server;

import com.tuyage.proto.CallPacket;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class CallRegistry {
    private final Map<Long, StreamObserver<CallPacket>> streams = new ConcurrentHashMap<>();

    void register(long userId, StreamObserver<CallPacket> observer) {
        // IMPORTANT: gRPC n’autorise setOnCancelHandler() que pendant l’appel
        // initial de la méthode de service, avant son retour. Le handler est donc
        // installé dans ChatServiceImpl.call(...), et jamais ici lors du premier
        // paquet CALL_REGISTER.
        streams.put(userId, observer);
    }

    void unregister(long userId, StreamObserver<CallPacket> observer) {
        streams.remove(userId, observer);
    }

    void unregisterUser(long userId) {
        StreamObserver<CallPacket> observer = streams.remove(userId);
        if (observer != null) {
            try { observer.onCompleted(); } catch (Exception ignored) {}
        }
    }

    boolean send(long userId, CallPacket packet) {
        StreamObserver<CallPacket> observer = streams.get(userId);
        if (observer == null) return false;
        try {
            synchronized (observer) {
                observer.onNext(packet);
            }
            return true;
        } catch (Exception e) {
            streams.remove(userId, observer);
            return false;
        }
    }
}
