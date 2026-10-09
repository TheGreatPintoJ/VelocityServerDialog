package me.pintoadmin.velocityServerDialog;

import com.github.retrooper.packetevents.*;
import com.github.retrooper.packetevents.protocol.dialog.*;
import com.github.retrooper.packetevents.wrapper.configuration.server.*;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.*;
import com.velocitypowered.api.scheduler.*;
import net.kyori.adventure.text.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

import static me.pintoadmin.velocityServerDialog.Text.mm;

public final class SelectionManager {
    public static final String NAMESPACE = "velocityserverdialog";

    private record Pending(PlayerChooseInitialServerEvent event, Continuation continuation,
                           ScheduledTask timeoutTask, Set<String> onlineServers, Dialog dialog) {}

    private void resend(Pending p, boolean postjoin) {
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(p.event().getPlayer(), postjoin ? new WrapperPlayServerShowDialog(p.dialog()) : new WrapperConfigServerShowDialog(p.dialog()));
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Pending> passwdPendings = new HashMap<>();
    private final Object plugin;
    private final ProxyServer proxy;
    private final ConfigLoader config;
    private final Logger logger;

    public SelectionManager(Object plugin, ProxyServer proxy, ConfigLoader config, Logger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.config = config;
        this.logger = logger;
    }

    public List<RegisteredServer> listedServers() {
        if (config.servers().isEmpty()) return List.copyOf(proxy.getAllServers());
        return config.servers().stream()
                .map(e -> proxy.getServer(e.velocity()))
                .flatMap(Optional::stream)
                .toList();
    }

    public void begin(PlayerChooseInitialServerEvent event, Continuation cont, Map<String, Boolean> online, boolean postjoin) {
        Player player = event.getPlayer();
        if (!player.isActive() && cont != null) { cont.resume(); return; }

        // Feature 5: on timeout, close the dialog and fall back to Velocity's "try" list
        ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> {
            Pending p = pending.remove(player.getUniqueId());   // atomic: only one winner
            if (p == null) return;
            PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(player, postjoin ? new WrapperPlayServerClearDialog() : new WrapperConfigServerClearDialog());
            Optional<RegisteredServer> defaultServer = listedServers().stream()
                    .filter(s -> s.getServerInfo().getName().equalsIgnoreCase(config.defaultServer()))
                    .findFirst();
            defaultServer.ifPresent(event::setInitialServer); // Set destination to default-server if it exists
            if(cont != null) p.continuation().resume();                            // resume velocity server placement
        }).delay(config.timeoutSeconds(), TimeUnit.SECONDS).schedule();

        Set<String> up = online.entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
        // Feature 2: build and send the dialog

//        logger.warn(player.getUsername() + " is connecting using: " + player.getClientBrand());
        String brand = player.getClientBrand().split(":")[0];

        // loop through all servers
        // build dialog for each passwd dialog
        // add to passwdPendings
        for (ConfigLoader.Entry entry : config.servers()){
            String passwd = entry.pwString();
            if(passwd.isBlank()) continue;

            String velocity = entry.velocity();

            Dialog pwDialog = PasswordDialogFactory.build(config, velocity);
            Pending pwP = new Pending(event, cont, task, up, pwDialog);
            passwdPendings.put(velocity, pwP);
        }

        Dialog dialog = ServerDialogFactory.build(config, listedServers(), online, player, brand);
        Pending p = new Pending(event, cont, task, up, dialog);
        pending.put(player.getUniqueId(), p);
        resend(p, postjoin);
    }

    public void sendServersDialog(CommandSource source){
        Map<String, Boolean> online = new ConcurrentHashMap<>();
        CompletableFuture<?>[] pings = listedServers().stream()
                .map(s -> s.ping()
                        .orTimeout(1500, TimeUnit.MILLISECONDS)
                        .handle((ping, err) -> online.put(s.getServerInfo().getName(), err == null)))
                .toArray(CompletableFuture[]::new);
        Player p = (Player) source;
        begin(new PlayerChooseInitialServerEvent(p, null), null, online, true);
    }

    /** Feature 4. Called from the PacketEvents thread. */
    public void select(UUID uuid, String serverName, boolean postjoin) {
        Pending p = pending.get(uuid);
        if (p == null) return;

        Optional<RegisteredServer> target = listedServers().stream()
                .filter(s -> s.getServerInfo().getName().equalsIgnoreCase(serverName))
                .findFirst();
        // Never trust the client: unknown or offline means ignore. The dialog stays open
        // because afterAction=WAIT_FOR_RESPONSE, so re-send it to make it clickable again.
        if (target.isEmpty() || !p.onlineServers().contains(target.get().getServerInfo().getName())) {
            resend(p, postjoin);
            return;
        }
        if (!pending.remove(uuid, p)) return;                   // lost race with timeout
        p.timeoutTask().cancel();

        Player player = p.event().getPlayer();
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(player, postjoin ? new WrapperPlayServerClearDialog() : new WrapperConfigServerClearDialog());
        p.event().setInitialServer(target.get());
        if(p.continuation() != null) p.continuation().resume();
    }

    public void passwd(UUID uuid, String serverName, boolean postjoin){
        Pending p = pending.get(uuid);
        if (p == null) return;

        Player player = p.event().getPlayer();
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(player, postjoin ? new WrapperPlayServerClearDialog() : new WrapperConfigServerClearDialog());

        Pending pw = passwdPendings.get(serverName);
        resend(pw, postjoin);
    }

    public void passwd_back(UUID uuid, boolean postjoin){
        Pending p = pending.get(uuid);
        if (p == null) return;
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(p.event.getPlayer(), postjoin ? new WrapperPlayServerClearDialog() : new WrapperConfigServerClearDialog());
        resend(p, postjoin);
    }

    public boolean checkPasswd(String server, String passwd){
        Optional<ConfigLoader.Entry> entry = config.servers().stream().filter(e -> e.velocity().equalsIgnoreCase(server)).findFirst();
        return entry.isPresent() && entry.get().pwString().equals(passwd);
    }

    public void quit(UUID uuid, boolean postjoin) {
        Pending p = pending.remove(uuid);
        if (p == null) return;
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(p.event.getPlayer(), postjoin ? new WrapperPlayServerClearDialog() : new WrapperConfigServerClearDialog());
        p.timeoutTask().cancel();
        p.event().getPlayer().disconnect(mm(config.kickQuit()));
        if(p.continuation() != null) p.continuation().resume();
    }

    /** Feature 7: the player disconnected on their own. */
    public void abandon(UUID uuid) {
        Pending p = pending.remove(uuid);
        if (p == null) return;
        p.timeoutTask().cancel();
        if(p.continuation() != null) p.continuation().resume();
    }
}