package me.pintoadmin.velocityServerDialog;

import com.sun.tools.javac.*;
import com.velocitypowered.api.command.*;
import org.slf4j.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static me.pintoadmin.velocityServerDialog.Text.mm;

public class MaintenanceCommand implements SimpleCommand {
    private final ConfigLoader configLoader;
    private final SelectionManager manager;
    private final Path dataDir;
    private final Logger logger;

    public MaintenanceCommand(ConfigLoader configLoader, SelectionManager manager, Path dataDir, Logger logger) {
        this.configLoader = configLoader;
        this.manager = manager;
        this.dataDir = dataDir;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

        String[] args = invocation.arguments();
        if(args.length == 0) {
            if(!source.hasPermission("velocityserverdialog.serversdialog")) return;
            manager.sendServersDialog(invocation.source());
        } else {
            if(!source.hasPermission("velocityserverdialog.serversdialog.maintenance")) return;
            if (args.length < 2) source.sendMessage(mm("<red>Usage: /serversdialog <server> <maintenance: true|false>"));
            else {
                String server = args[0];
                boolean enabled = Boolean.parseBoolean(args[1]);
                try {
                    configLoader.setMaintenance(dataDir, server, enabled);
                    source.sendMessage(mm("<green>Set maintenance status of " + server + " to " + enabled));
                } catch (IOException e) {
                    source.sendMessage(mm("<red>An error occured while setting maintenance mode for server: " + server));
                    source.sendMessage(mm("<red>Check the console for more details"));
                    logger.error(e.getStackTrace().toString());
                }
            }
        }
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();

        if(!invocation.source().hasPermission("velocityserversdialog.serversdialog.maintenance"))
            return CompletableFuture.completedFuture(List.of());

        if (args.length <= 1) {
            return CompletableFuture.completedFuture(configLoader.servers().stream().map(e -> e.velocity()).toList());
        } else if (args.length == 2) {
            return CompletableFuture.completedFuture(List.of("true", "false"));
        }
        return CompletableFuture.completedFuture(List.of());
    }
}
