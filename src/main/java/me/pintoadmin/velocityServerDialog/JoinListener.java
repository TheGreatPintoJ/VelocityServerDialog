package me.pintoadmin.velocityServerDialog;

import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.network.*;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.*;
import com.velocitypowered.api.proxy.server.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

import static me.pintoadmin.velocityServerDialog.Text.*;

public final class JoinListener {
    private final ProxyServer proxy;
    private final Logger logger;
    private final ConfigLoader config;
    private final SelectionManager manager;

    public JoinListener(ProxyServer proxy, Logger logger, ConfigLoader config, SelectionManager manager) {
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.manager = manager;
    }

    @Subscribe
    public EventTask onChooseInitial(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();

        // Feature 6: no dialog support before 1.21.6
        if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_21_6)) {
            if (config.kickLegacyClients()) player.disconnect(mm(config.kickQuit()));
            return null; // leave Velocity's default choice untouched
        }

        manager.playersPostjoin.put(player, false);

        // Feature 1: suspend the event; Velocity won't connect until resume()
        return EventTask.withContinuation(continuation -> manager.begin(event, continuation));
    }

    // Feature 7: player closed the game while choosing
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        manager.abandon(event.getPlayer().getUniqueId());
        manager.playersPostjoin.remove(event.getPlayer());
    }
}