package me.pintoadmin.velocityServerDialog;

import com.github.retrooper.packetevents.*;
import com.github.retrooper.packetevents.event.*;
import com.google.inject.*;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.*;
import com.velocitypowered.api.plugin.annotation.*;
import com.velocitypowered.api.proxy.*;
import io.github.retrooper.packetevents.velocity.factory.*;
import org.slf4j.*;

import java.io.*;
import java.nio.file.*;

public class VelocityServerDialog {
    private final ProxyServer proxy;
    private final PluginContainer container;
    private final Logger logger;
    @Inject
    @DataDirectory
    private Path dataDir;

    @Inject
    public VelocityServerDialog(ProxyServer proxy, PluginContainer container, Logger logger) {
        this.proxy = proxy;
        this.container = container;
        this.logger = logger;
    }

    private DBManager dbManager;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) throws IOException {
        PacketEvents.setAPI(VelocityPacketEventsBuilder.build(proxy, container, logger, dataDir));
        PacketEvents.getAPI().getSettings()
                .checkForUpdates(false)
                .bStats(true);
        PacketEvents.getAPI().load();
        PacketEvents.getAPI().init();

        ConfigLoader configLoader = ConfigLoader.load(dataDir);
        SelectionManager manager = new SelectionManager(this, proxy, configLoader, logger);
        dbManager = new DBManager(proxy, logger, dataDir);
        dbManager.init();

        proxy.getEventManager().register(this, new JoinListener(proxy, configLoader, manager));
        PacketEvents.getAPI().getEventManager()
                .registerListener(new ClickPacketListener(proxy, manager, dbManager, configLoader), PacketListenerPriority.NORMAL);

        SimpleCommand command = new MaintenanceCommand(configLoader, manager, dataDir, logger);

        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("serversdialog").build(),
                command);
    }

    @Subscribe
    public void onProxyShutdownEvent(ProxyShutdownEvent event) {
        PacketEvents.getAPI().terminate();
        dbManager.close();
    }
}
