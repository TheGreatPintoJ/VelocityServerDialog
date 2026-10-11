package me.pintoadmin.velocityServerDialog;

import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.network.*;
import com.velocitypowered.api.proxy.*;
import net.kyori.adventure.text.*;

import java.util.*;

import static me.pintoadmin.velocityServerDialog.Text.*;

public final class JoinListener {
    private final ProxyServer proxy;
    private final ConfigLoader config;
    private final SelectionManager manager;

    public JoinListener(ProxyServer proxy, ConfigLoader config, SelectionManager manager) {
        this.proxy = proxy;
        this.config = config;
        this.manager = manager;
    }

    @Subscribe
    public EventTask onChooseInitial(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();

        // no dialog support before 1.21.6
        if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_21_6)) {
            if (config.kickLegacyClients()) player.disconnect(mm(config.kickQuit()));
            return null; // leave Velocity's default choice untouched
        }

        manager.playersPostjoin.put(player, false);

        // suspend the event; Velocity won't connect until resume()
        return EventTask.withContinuation(continuation -> manager.begin(event, continuation));
    }

    @Subscribe
    public void onInitialServerFailure(KickedFromServerEvent event) {
        if (!manager.playersPostjoin.get(event.getPlayer())) return;

        Optional<Component> reasonOpt = event.getServerKickReason();

        manager.showDialog(event.getPlayer(),
                NoticeDialogFactory.build(
                        mm("<red>Could not connect to server: ")
                                .append(reasonOpt
                                        .orElse(Component.text("Unknown reason")))));
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        manager.playersPostjoin.put(event.getPlayer(), true);
    }

    // player closed the game while choosing
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        manager.playersPostjoin.remove(event.getPlayer());
    }
}