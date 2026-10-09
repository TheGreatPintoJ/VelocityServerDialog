package me.pintoadmin.velocityServerDialog;

import com.github.retrooper.packetevents.*;
import com.github.retrooper.packetevents.event.*;
import com.google.inject.Inject;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.annotation.*;
import com.velocitypowered.api.proxy.*;
import net.kyori.adventure.text.*;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.*;

import static me.pintoadmin.velocityServerDialog.Text.mm;

public class VelocityServerDialog {
    private final ProxyServer proxy;
    private final Logger logger;
    @Inject @DataDirectory
    private Path dataDir;

    @Inject
    public VelocityServerDialog(ProxyServer proxy, Logger logger){
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) throws IOException {
        ConfigLoader configLoader = ConfigLoader.load(dataDir);
        SelectionManager manager = new SelectionManager(this, proxy, configLoader, logger);

        proxy.getEventManager().register(this, new JoinListener(proxy, logger, configLoader, manager));
        PacketEvents.getAPI().getEventManager()
                .registerListener(new ClickPacketListener(proxy, manager), PacketListenerPriority.NORMAL);

        SimpleCommand serversCommand = invocation -> manager.sendServersDialog(invocation.source());
        SimpleCommand maintenanceCommand = invocation -> {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();
            if(args.length < 2) source.sendMessage(mm("<red>Usage: /serversdialogmaintenance <server> <true|false>"));
            else {
                String server = args[0];
                boolean enabled = Boolean.parseBoolean(args[1]);
                try {
                    configLoader.setMaintenance(dataDir, server, enabled);
                } catch (IOException e) {
                    source.sendMessage(mm("<red>An error occured while setting maintenance mode for server: "+server));
                    source.sendMessage(mm("<red>Check the console for more details"));
                    logger.error(e.getStackTrace().toString());
                }
            }
        };

        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("serversdialog").build(),
                serversCommand);
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("serversdialogmaintenance").build(),
                maintenanceCommand);
    }
}
