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
import net.kyori.adventure.text.event.ClickEvent;
import org.slf4j.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

import static me.pintoadmin.velocityServerDialog.Text.mm;

public final class SelectionManager {
    public static final String NAMESPACE = "velocityserverdialog";
    public final Map<Player, Boolean> playersPostjoin = new HashMap<>();

    private record Pending(PlayerChooseInitialServerEvent event, Continuation continuation,
                           ScheduledTask timeoutTask, Set<String> onlineServers, Dialog dialog) {}

    /**
     * Resend a Pending object to its player - correct for player state (playing/config)
     * @param p the Pending to send
     */
    private void resend(Pending p) {
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(p.event().getPlayer(), playersPostjoin.get(p.event.getPlayer()) ? new WrapperPlayServerShowDialog(p.dialog()) : new WrapperConfigServerShowDialog(p.dialog()));
    }

    /**
     * Clear the dialog from the player - correct for player state (playing/config)
     * @param player the player to clear from
     */
    public void clearDialog(Player player){
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(player, playersPostjoin.get(player) ? new WrapperPlayServerClearDialog() : new WrapperConfigServerClearDialog());
    }

    /**
     * Show a dialog to a player - correct for player state (playing/config)
     * @param player the player to send the dialog to
     * @param dialog the dialog to send to the player
     */
    public void showDialog(Player player, Dialog dialog){
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(player, playersPostjoin.get(player) ? new WrapperPlayServerShowDialog(dialog) : new WrapperConfigServerShowDialog(dialog));
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Pending> passwdPendings = new HashMap<>();
    private final Object plugin;
    private final ProxyServer proxy;
    private final ConfigLoader config;
    private final Logger logger;

    /**
     * Class to handle dialog selections and dialog sending
     * @param plugin this plugin
     * @param proxy the operating proxy
     * @param config the ConfigLoader instance to load from
     * @param logger the proxy's logger
     */
    public SelectionManager(Object plugin, ProxyServer proxy, ConfigLoader config, Logger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.config = config;
        this.logger = logger;
    }

    /**
     * @return the list of servers from the config. Teturns all from proxy if none specified in config
     */
    public List<RegisteredServer> listedServers() {
        if (config.servers().isEmpty()) return List.copyOf(proxy.getAllServers());
        return config.servers().stream()
                .map(e -> proxy.getServer(e.velocity()))
                .flatMap(Optional::stream)
                .toList();
    }

    public List<RegisteredServer> getGroupServers(String group){
        if (config.servers().isEmpty()) return List.of();
        return config.servers().stream()
                .filter(e -> e.group() != null && e.group().equalsIgnoreCase(group))
                .map(e -> proxy.getServer(e.velocity()))
                .flatMap(Optional::stream)
                .toList();
    }

    public List<RegisteredServer> getNonGroupServers(){
        if (config.servers().isEmpty()) return List.of();
        return config.servers().stream()
                .filter(e -> e.group() == null)
                .map(e -> proxy.getServer(e.velocity()))
                .flatMap(Optional::stream)
                .toList();
    }

    public List<String> listedGroups(){
        if (config.groups().isEmpty()) return List.of();
        return config.groups().stream()
                .map(ConfigLoader.Group::id)
                .toList();
    }

    public Map<String, Boolean> getOnlineServers() {
        Map<String, Boolean> online = new ConcurrentHashMap<>();
        CompletableFuture<?>[] pings = listedServers().stream()
                .map(s -> s.ping()
                        .orTimeout(1500, TimeUnit.MILLISECONDS)
                        .handle((ping, err) -> online.put(s.getServerInfo().getName(), err == null)))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(pings).get(1500, TimeUnit.MILLISECONDS);
            return online;
        } catch (InterruptedException | ExecutionException | TimeoutException e){
            Map<String, Boolean> offlineList = new HashMap<>();
            listedServers().stream()
                    .map(s -> s.getServerInfo().getName())
                    .forEach(s -> offlineList.put(s, false));
            return offlineList;
        }
    }

    /**
     * Begin the server picker dialog
     * @param event the PlayerChooseInitialServerEvent to interact with
     * @param cont the Continuation of the proxy's placement task
     */
    public void begin(PlayerChooseInitialServerEvent event, Continuation cont) {
        Player player = event.getPlayer();
        if (!player.isActive() && cont != null) { cont.resume(); return; }

        // Feature 5: on timeout, close the dialog and fall back to Velocity's "try" list
        Scheduler.TaskBuilder taskBuilder = proxy.getScheduler().buildTask(plugin, () -> {
            Pending p = pending.remove(player.getUniqueId());   // atomic: only one winner
            if (p == null) return;
            clearDialog(p.event.getPlayer());
            Optional<RegisteredServer> defaultServer = listedServers().stream()
                    .filter(s -> s.getServerInfo().getName().equalsIgnoreCase(config.defaultServer()))
                    .findFirst();
            defaultServer.ifPresent(event::setInitialServer); // Set destination to default-server if it exists
            if(config.kickOnTimeout()) p.event.getPlayer().disconnect(mm("<red>You timed out! Select a server."));
            if(cont != null) p.continuation().resume();                            // resume velocity server placement
        }).delay(config.timeoutSeconds(), TimeUnit.SECONDS);

        ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> {}).schedule(); // if postjoin, empty task instead (no dialog timeout)
        if(!playersPostjoin.get(player)) task = taskBuilder.schedule(); // If not postjoin, schedule dialog timeout

        Map<String, Boolean> online = getOnlineServers();
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

        Dialog dialog = ServerDialogFactory.build(this, config, listedGroups(), getNonGroupServers(), online, player, brand);
        Pending p = new Pending(event, cont, task, up, dialog);
        pending.put(player.getUniqueId(), p);
        resend(p);
    }

    /**
     * Sends the main server picker dialog to a CommandSource
     * @param source the CommandSource to send the dialog to
     */
    public void sendServersDialog(CommandSource source){
        Player p = (Player) source;
        begin(new PlayerChooseInitialServerEvent(p, null), null);
    }

    /**
     * Signifies a selection click
     * @param uuid the uuid of the player who clicked
     * @param serverName the name of the server the player clicked on
     */
    public void select(UUID uuid, String serverName) {
        Pending p = pending.get(uuid);
        if (p == null) return;

        Optional<RegisteredServer> target = listedServers().stream()
                .filter(s -> s.getServerInfo().getName().equalsIgnoreCase(serverName))
                .findFirst();
        // Never trust the client: unknown or offline means ignore. The dialog stays open
        // because afterAction=WAIT_FOR_RESPONSE, so re-send it to make it clickable again.
        if (target.isEmpty() || !p.onlineServers().contains(target.get().getServerInfo().getName())) { // if server doesn't exist or is not online
            resend(p);
            return;
        }
        if (!pending.remove(uuid, p)) return;                   // lost race with timeout
        p.timeoutTask().cancel();

        Player player = p.event().getPlayer();
        clearDialog(player);
        showDialog(player, WaitingDialogFactory.build(serverName));

        if(playersPostjoin.get(player)) player.createConnectionRequest(target.get()).connect().thenAccept((result) -> {
            clearDialog(player);
            if(!result.isSuccessful()){
                showDialog(player,
                        NoticeDialogFactory.build(
                                mm("<red>Could not connect to server: ")
                                        .append(result.getReasonComponent()
                                                .orElse(Component.text("Unknown reason")))));
            }
        });
        else p.event().setInitialServer(target.get());

        if(p.continuation() != null) p.continuation().resume();
    }

    public void group(UUID uuid, String group){
        Optional<Player> player = proxy.getPlayer(uuid);
        if(player.isEmpty()) return;

        List<RegisteredServer> groupServers = getGroupServers(group);

        clearDialog(player.get());
        showDialog(player.get(),
                ServerDialogFactory.build(this, config,
                        List.of(), groupServers,
                        getOnlineServers(), player.get(),
                        player.get().getClientBrand().split(":")[0]));
    }

    /**
     * Signifies a click on a password-protected server
     * @param uuid the uuid of the player who clicked
     * @param serverName the name of the server the player clicked on
     */
    public void passwd(UUID uuid, String serverName){
        Pending p = pending.get(uuid);
        if (p == null) return;

        Player player = p.event().getPlayer();
        clearDialog(player);

        showDialog(player, PasswordDialogFactory.build(config, serverName));
    }

    /**
     * Sends a custom dialog to a player, called from the PacketEvents thread
     * @param uuid the uuid of the player to send the dialog to
     * @param dialog the dialog to send to the player
     */
    public void custom(UUID uuid, Dialog dialog){
        Optional<Player> player = proxy.getPlayer(uuid);
        player.ifPresent(value -> showDialog(value, dialog));
    }

    /**
     * Signifies a click on a 'back' button. Sends the player back to the main server picker dialog
     * @param uuid the uuid of the player who clicked
     */
    public void back(UUID uuid){
        Pending p = pending.get(uuid);
        if (p == null) return;
        clearDialog(p.event.getPlayer());
        resend(p);
    }

    /**
     * Checks the inputted password against the configuration file's password
     * @param server the server in question
     * @param passwd the password inputted by a player
     * @return true if the password matches, false if not
     */
    public boolean checkPasswd(String server, String passwd){
        Optional<ConfigLoader.Entry> entry = config.servers().stream().filter(e -> e.velocity().equalsIgnoreCase(server)).findFirst();
        return entry.isPresent() && entry.get().pwString().equals(passwd);
    }

    /**
     * Signifies a player clicking the 'disconnect' button. Disconnects the player
     * @param uuid the player who clicked
     */
    public void quit(UUID uuid) {
        Pending p = pending.remove(uuid);
        Optional<Player> player = proxy.getPlayer(uuid);
        if(player.isEmpty()) return;
        clearDialog(player.get());
        if(p != null) p.timeoutTask().cancel();
        player.get().disconnect(mm(config.kickQuit()));
        if(p != null && p.continuation() != null) p.continuation().resume();
    }

    /**
     * Signifiesd a player clicking the 'close' button. Closes the currently open dialog
     * @param uuid the player who clicked
     */
    public void close(UUID uuid){
        Pending p = pending.remove(uuid);
        if (p == null) return;
        clearDialog(p.event.getPlayer());
        p.timeoutTask.cancel();
        if(p.continuation() != null) p.continuation().resume();
    }

    /**
     * Called if the player disconnected independently. Cleanup tasks
     * @param uuid the player who disconnected
     */
    public void abandon(UUID uuid) {
        Pending p = pending.remove(uuid);
        if (p == null) return;
        p.timeoutTask().cancel();
        if(p.continuation() != null) p.continuation().resume();
    }
}