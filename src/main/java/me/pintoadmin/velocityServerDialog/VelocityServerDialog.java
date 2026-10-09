package me.pintoadmin.velocityServerDialog;

import com.github.retrooper.packetevents.*;
import com.github.retrooper.packetevents.event.*;
import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.annotation.*;
import com.velocitypowered.api.proxy.*;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.*;

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
                .registerListener(new ClickPacketListener(manager), PacketListenerPriority.NORMAL);

        logger.info("VelocityServerDialog Initialized!");
    }
}
