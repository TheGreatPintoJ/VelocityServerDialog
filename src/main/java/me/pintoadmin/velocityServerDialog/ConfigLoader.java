package me.pintoadmin.velocityServerDialog;

import com.moandjiezana.toml.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public record ConfigLoader(
        int timeoutSeconds, boolean kickOnTimeout, boolean kickLegacyClients, int columns,
       String defaultServer, String maintenancePerm, boolean savePasswords, String title, String body,
       String kickQuit, List<Entry> servers){
    private static Path dataDir = Path.of("");

    public record Entry(String velocity, String display, String description, String type, String permString, String showPermString, String pwString, boolean maintenance) {}

    public Entry entry(String velocityName) {
        return servers.stream().filter(e -> e.velocity().equalsIgnoreCase(velocityName)).findFirst()
                .orElse(new Entry(velocityName, velocityName, "", "vanilla", "", "", "", false));
    }

    public static ConfigLoader load(Path dataDir) throws IOException {
        ConfigLoader.dataDir = dataDir;
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
            String showPerm = t.getString("show-permission", "");
            String pass = t.getString("password", "");
            boolean maintenance = t.getBoolean("maintenance", false);

            servers.add(new Entry(velocityName, display, description, type, perm, showPerm, pass, maintenance));
        }
        int timeout = Math.clamp(toml.getLong("timeout-seconds", 15L), 2, 25);
        return new ConfigLoader(
                timeout,
                "kick".equalsIgnoreCase(toml.getString("on-timeout", "default")),
                "kick".equalsIgnoreCase(toml.getString("legacy-clients", "default")),
                toml.getLong("columns", 1L).intValue(),
                toml.getString("default-server", ""),
                toml.getString("maintenance-perm", ""),
                toml.getBoolean("save-passwords", false),
                toml.getString("title"), toml.getString("body"),
                toml.getString("kick-quit-message", "<gray>Goodbye!"),
                servers);
    }

    public ConfigLoader reload() throws IOException {
        return load(dataDir);
    }

    public void setMaintenance(Path dataDir, String serverName, boolean maintenance) throws IOException {
        List<Entry> servers = load(dataDir).servers();
        Path file = dataDir.resolve("config.toml");
        String config = Files.readString(file);

        int serverIndex = -1;
        for (int i = 0; i < servers.size(); i++) {
            if (serverName.equalsIgnoreCase(servers.get(i).velocity())) {
                serverIndex = i;
                break;
            }
        }
        if (serverIndex < 0) {
            throw new IllegalArgumentException("Unknown server: " + serverName);
        }

        Pattern sectionPattern =
                Pattern.compile("(?m)^[ \\t]*\\[\\[server\\]\\][ \\t]*(?:#.*)?\\r?$");
        Matcher sections = sectionPattern.matcher(config);
        List<Integer> starts = new ArrayList<>();
        while (sections.find()) {
            starts.add(sections.start());
        }
        if (starts.size() != servers.size()) {
            throw new IOException("Could not match server entries in config.toml");
        }

        int start = starts.get(serverIndex);
        int end = serverIndex + 1 < starts.size() ? starts.get(serverIndex + 1) : config.length();
        String section = config.substring(start, end);
        Pattern maintenancePattern = Pattern.compile(
                "(?m)^([ \\t]*maintenance[ \\t]*=[ \\t]*)(?:true|false)([ \\t]*(?:#.*)?)(?=\\r?$)");
        Matcher setting = maintenancePattern.matcher(section);
        String updatedSection;
        if (setting.find()) {
            updatedSection = setting.replaceFirst("$1" + maintenance + "$2");
        } else {
            String lineEnding = config.contains("\r\n") ? "\r\n" : "\n";
            String separator = section.endsWith("\n") || section.endsWith("\r") ? "" : lineEnding;
            updatedSection = section + separator + "maintenance = " + maintenance + lineEnding;
        }

        Files.writeString(file, config.substring(0, start) + updatedSection + config.substring(end));
    }
}