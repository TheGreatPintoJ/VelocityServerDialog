package me.pintoadmin.velocityServerDialog;

import com.moandjiezana.toml.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

public record ConfigLoader(
        int timeoutSeconds, boolean kickLegacyClients, int columns,
       String defaultServer, String title, String body,
       String kickQuit, List<Entry> servers){

    public record Entry(String velocity, String display, String description, String type, String permString, String pwString) {}

    public Entry entry(String velocityName) {
        return servers.stream().filter(e -> e.velocity().equalsIgnoreCase(velocityName)).findFirst()
                .orElse(new Entry(velocityName, velocityName, "", "vanilla", "", ""));
    }

    public static ConfigLoader load(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("config.toml");
        if (Files.notExists(file)){
            try (InputStream in = ConfigLoader.class.getResourceAsStream("/config.toml")) {
                Files.copy(Objects.requireNonNull(in), file);
            }
        }
        Toml toml = new Toml().read(file.toFile());
        List<Entry> servers = new ArrayList<>();
        for (Toml t : toml.getTables("server")){
            String velocityName = t.getString("velocity");
            String display = t.getString("display");
            String description = t.getString("description");
            String type = t.getString("type");
            String perm = t.getString("permission", "");
            String pass = t.getString("password", "");

            servers.add(new Entry(velocityName, display, description, type, perm, pass));
        }
        int timeout = Math.clamp(toml.getLong("timeout-seconds", 15L), 2, 25);
        return new ConfigLoader(
                timeout,
                "kick".equalsIgnoreCase(toml.getString("legacy-clients", "default")),
                toml.getLong("columns", 1L).intValue(),
                toml.getString("default-server"),
                toml.getString("title"), toml.getString("body"),
                toml.getString("kick-quit-message"),
                servers);
    }
}