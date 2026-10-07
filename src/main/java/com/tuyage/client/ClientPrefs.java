package com.tuyage.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Properties;
import java.util.stream.Collectors;

final class ClientPrefs {
    private final Path file = Path.of(System.getProperty("user.home"), ".tuyage-client.properties");
    private final Properties p = new Properties();

    ClientPrefs() {
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (Exception ignored) { }
        }
    }

    String server() { return p.getProperty("server", "localhost:9090"); }
    boolean dark() { return Boolean.parseBoolean(p.getProperty("dark", "false")); }
    String username() { return p.getProperty("username", ""); }
    String chatBackground() { return p.getProperty("chatBackground", ""); }

    Set<Long> mutedUsers() { return longSet("mutedUsers"); }
    Set<Long> mutedGroups() { return longSet("mutedGroups"); }
    Set<Long> pinnedUsers() { return longSet("pinnedUsers"); }
    Set<Long> pinnedGroups() { return longSet("pinnedGroups"); }
    Set<Long> archivedUsers() { return longSet("archivedUsers"); }
    Set<Long> archivedGroups() { return longSet("archivedGroups"); }

    void toggleMutedUser(long id) { toggle("mutedUsers", id); }
    void toggleMutedGroup(long id) { toggle("mutedGroups", id); }
    void togglePinnedUser(long id) { toggle("pinnedUsers", id); }
    void togglePinnedGroup(long id) { toggle("pinnedGroups", id); }
    void toggleArchivedUser(long id) { toggle("archivedUsers", id); }
    void toggleArchivedGroup(long id) { toggle("archivedGroups", id); }

    boolean mutedUser(long id) { return mutedUsers().contains(id); }
    boolean mutedGroup(long id) { return mutedGroups().contains(id); }
    boolean pinnedUser(long id) { return pinnedUsers().contains(id); }
    boolean pinnedGroup(long id) { return pinnedGroups().contains(id); }
    boolean archivedUser(long id) { return archivedUsers().contains(id); }
    boolean archivedGroup(long id) { return archivedGroups().contains(id); }

    void save(String server, boolean dark, String username) {
        p.setProperty("server", server == null || server.isBlank() ? "localhost:9090" : server.trim());
        p.setProperty("dark", Boolean.toString(dark));
        p.setProperty("username", username == null ? "" : username);
        persist();
    }

    void saveChatBackground(String path) {
        p.setProperty("chatBackground", path == null ? "" : path);
        persist();
    }

    private Set<Long> longSet(String key) {
        Set<Long> out = new LinkedHashSet<>();
        String raw = p.getProperty(key, "");
        if (raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            try { out.add(Long.parseLong(part.trim())); } catch (Exception ignored) { }
        }
        return out;
    }

    private void toggle(String key, long id) {
        Set<Long> values = longSet(key);
        if (!values.add(id)) values.remove(id);
        p.setProperty(key, values.stream().map(String::valueOf).collect(Collectors.joining(",")));
        persist();
    }

    private void persist() {
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "Tuyage Messenger preferences");
        } catch (Exception ignored) { }
    }
}
