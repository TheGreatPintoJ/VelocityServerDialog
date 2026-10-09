package me.pintoadmin.serverSelector;

import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.network.*;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.*;

import java.util.*;
import java.util.concurrent.*;

import static me.pintoadmin.serverSelector.Text.mm;

public final class JoinListener {
    private final ProxyServer proxy;
    private final SelectorConfig config;
    private final SelectionManager manager;

    public JoinListener(ProxyServer proxy, SelectorConfig config, SelectionManager manager) {
        this.proxy = proxy;
        this.config = config;
        this.manager = manager;
    }

    @Subscribe
    public EventTask onChooseInitial(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();

        // Feature 6: no dialog support before 1.21.6
        if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_21_6)) {
            if (config.kickLegacyClients()) player.disconnect(mm(config.kickLegacy()));
            return null; // leave Velocity's default choice untouched
        }

        // Feature 1: suspend the event; Velocity won't connect until resume()
        return EventTask.withContinuation(continuation -> {
            List<RegisteredServer> servers = manager.listedServers();

            // Feature 3: ping all servers in parallel, capped at 1.5s
            Map<String, Boolean> online = new ConcurrentHashMap<>();
            CompletableFuture<?>[] pings = servers.stream()
                    .map(s -> s.ping()
                            .orTimeout(1500, TimeUnit.MILLISECONDS)
                            .handle((ping, err) -> online.put(s.getServerInfo().getName(), err == null)))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(pings).whenComplete((v, err) ->
                    manager.begin(event, continuation, online));
        });
    }

    // Feature 7: player closed the game while choosing
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        manager.abandon(event.getPlayer().getUniqueId());
    }
}