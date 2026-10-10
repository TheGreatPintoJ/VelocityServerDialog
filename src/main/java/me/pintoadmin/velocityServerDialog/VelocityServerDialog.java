package me.pintoadmin.velocityServerDialog;

import com.github.retrooper.packetevents.*;
import com.github.retrooper.packetevents.event.*;
import com.google.inject.Inject;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.*;
import com.velocitypowered.api.proxy.*;
import io.github.retrooper.packetevents.velocity.factory.VelocityPacketEventsBuilder;
import net.kyori.adventure.text.*;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.*;

import static me.pintoadmin.velocityServerDialog.Text.mm;

public class VelocityServerDialog {
    private final ProxyServer proxy;
    private final PluginContainer container;
    private final Logger logger;
    @Inject @DataDirectory
    private Path dataDir;

    @Inject
    public VelocityServerDialog(ProxyServer proxy, PluginContainer container, Logger logger){
        this.proxy = proxy;
        this.container = container;
        this.logger = logger;
    }

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
        DBManager dbManager = new DBManager(proxy, logger, dataDir);

        proxy.getEventManager().register(this, new JoinListener(proxy, configLoader, manager));
        PacketEvents.getAPI().getEventManager()
                .registerListener(new ClickPacketListener(proxy, manager, dbManager, configLoader), PacketListenerPriority.NORMAL);

        SimpleCommand command = new MaintenanceCommand(configLoader, manager, dataDir, logger);

        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("serversdialog").build(),
                command);
    }
}
