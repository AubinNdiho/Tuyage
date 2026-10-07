package com.tuyage.server;

import com.tuyage.proto.Event;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Sessions d'authentification + présence temps réel.
 *
 * La présence ne dépend pas seulement de l'existence d'un token : un client qui
 * se ferme brutalement garde techniquement son token côté serveur. On suit donc
 * aussi le flux Subscribe actif et on applique une courte grâce de reconnexion
 * avant de passer l'utilisateur hors ligne.
 */
final class SessionRegistry {
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> userTokens = new ConcurrentHashMap<>();
    private final Map<Long, CopyOnWriteArrayList<StreamObserver<Event>>> observers = new ConcurrentHashMap<>();
    private final Set<Long> activeUsers = ConcurrentHashMap.newKeySet();
    private final Map<Long, ScheduledFuture<?>> offlineTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tuyage-presence");
        t.setDaemon(true);
        return t;
    });
    private volatile BiConsumer<Long, Boolean> presenceListener = (id, online) -> {};

    void setPresenceListener(BiConsumer<Long, Boolean> listener) {
        presenceListener = listener == null ? (id, online) -> {} : listener;
    }

    String create(long userId) {
        String token = UUID.randomUUID().toString();
        tokens.put(token, userId);
        userTokens.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(token);
        markOnline(userId);
        return token;
    }

    long require(String token) {
        Long userId = tokens.get(token);
        if (userId == null) throw new SecurityException("Session invalide. Reconnectez-vous.");
        return userId;
    }

    Long remove(String token) {
        Long userId = tokens.remove(token);
        if (userId != null) {
            Set<String> set = userTokens.get(userId);
            if (set != null) {
                set.remove(token);
                if (set.isEmpty()) userTokens.remove(userId);
            }
            if (!userTokens.containsKey(userId)) markOfflineNow(userId);
        }
        return userId;
    }

    void invalidateUser(long userId) {
        Set<String> set = userTokens.remove(userId);
        if (set != null) for (String token : set) tokens.remove(token);
        List<StreamObserver<Event>> list = observers.remove(userId);
        if (list != null) for (StreamObserver<Event> observer : list) { try { observer.onCompleted(); } catch (Exception ignored) {} }
        markOfflineNow(userId);
    }

    boolean isOnline(long userId) {
        return activeUsers.contains(userId);
    }

    void subscribe(long userId, StreamObserver<Event> observer) {
        cancelOfflineTask(userId);
        observers.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(observer);
        markOnline(userId);
        if (observer instanceof ServerCallStreamObserver<Event> serverObserver) {
            serverObserver.setOnCancelHandler(() -> unsubscribe(userId, observer));
        }
    }

    void unsubscribe(long userId, StreamObserver<Event> observer) {
        List<StreamObserver<Event>> list = observers.get(userId);
        if (list != null) {
            list.remove(observer);
            if (list.isEmpty()) observers.remove(userId);
        }
        if (!observers.containsKey(userId)) scheduleOffline(userId);
    }

    private void markOnline(long userId) {
        cancelOfflineTask(userId);
        if (activeUsers.add(userId)) {
            try { presenceListener.accept(userId, true); } catch (Throwable ignored) {}
        }
    }

    private void scheduleOffline(long userId) {
        cancelOfflineTask(userId);
        offlineTasks.put(userId, scheduler.schedule(() -> {
            offlineTasks.remove(userId);
            if (!observers.containsKey(userId)) markOfflineNow(userId);
        }, 5, TimeUnit.SECONDS));
    }

    private void markOfflineNow(long userId) {
        cancelOfflineTask(userId);
        if (activeUsers.remove(userId)) {
            try { presenceListener.accept(userId, false); } catch (Throwable ignored) {}
        }
    }

    private void cancelOfflineTask(long userId) {
        ScheduledFuture<?> task = offlineTasks.remove(userId);
        if (task != null) task.cancel(false);
    }

    void emitTo(long userId, Event event) {
        List<StreamObserver<Event>> copy = new ArrayList<>(observers.getOrDefault(userId, new CopyOnWriteArrayList<>()));
        for (StreamObserver<Event> observer : copy) {
            try {
                synchronized (observer) {
                    observer.onNext(event);
                }
            } catch (Exception ignored) {
                unsubscribe(userId, observer);
            }
        }
    }

    void emitToMany(Iterable<Long> userIds, Event event) {
        for (Long id : userIds) emitTo(id, event);
    }

    void broadcast(Event event) {
        for (Long id : observers.keySet()) emitTo(id, event);
    }
}
