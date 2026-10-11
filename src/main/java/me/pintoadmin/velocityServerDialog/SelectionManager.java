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

import static me.pintoadmin.velocityServerDialog.Text.*;

public final class SelectionManager {
    public static final String NAMESPACE = "velocityserverdialog";
    public final Map<Player, Boolean> playersPostjoin = new HashMap<>();
    private final Map<Player, PlayerChooseInitialServerEvent> playerInitialEvents = new HashMap<>();
    private final Map<Player, Continuation> eventContinuations = new HashMap<>();
    private final Map<Player, ScheduledTask> timeoutTasks = new HashMap<>();

    /**
     * Clear the dialog from the player - correct for player state (playing/config)
     *
     * @param player the player to clear from
     */
    public void clearDialog(Player player) {
        if (!playersPostjoin.containsKey(player)) return;
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(player, playersPostjoin.get(player) ? new WrapperPlayServerClearDialog() : new WrapperConfigServerClearDialog());
    }

    /**
     * Show a dialog to a player - correct for player state (playing/config)
     *
     * @param player the player to send the dialog to
     * @param dialog the dialog to send to the player
     */
    public void showDialog(Player player, Dialog dialog) {
        if (!playersPostjoin.containsKey(player)) return;
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(player, playersPostjoin.get(player) ? new WrapperPlayServerShowDialog(dialog) : new WrapperConfigServerShowDialog(dialog));
    }

    private final Object plugin;
    private final ProxyServer proxy;
    private final ConfigLoader config;
    private final Logger logger;

    /**
     * Class to handle dialog selections and dialog sending
     *
     * @param plugin this plugin
     * @param proxy  the operating proxy
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

    public List<RegisteredServer> getGroupServers(String group) {
        if (config.servers().isEmpty()) return List.of();
        return config.servers().stream()
                .filter(e -> e.group() != null && e.group().equalsIgnoreCase(group))
                .map(e -> proxy.getServer(e.velocity()))
                .flatMap(Optional::stream)
                .toList();
    }

    public List<RegisteredServer> getNonGroupServers() {
        if (config.servers().isEmpty()) return List.of();
        return config.servers().stream()
                .filter(e -> e.group() == null)
                .map(e -> proxy.getServer(e.velocity()))
                .flatMap(Optional::stream)
                .toList();
    }

    public List<String> listedGroups() {
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

        CompletableFuture.allOf(pings).join();
        return online;
    }

    /**
     * Begin the server picker dialog
     *
     * @param event the PlayerChooseInitialServerEvent to interact with
     * @param cont  the Continuation of the proxy's placement task
     */
    public void begin(PlayerChooseInitialServerEvent event, Continuation cont) {
        Player player = event.getPlayer();
        if (!player.isActive() && cont != null) {
            cont.resume();
            return;
        }

        // on timeout, close the dialog and fall back to Velocity's "try" list
        Scheduler.TaskBuilder taskBuilder = proxy.getScheduler().buildTask(plugin, () -> {
            clearDialog(player);
            Optional<RegisteredServer> defaultServer = listedServers().stream()
                    .filter(s -> s.getServerInfo().getName().equalsIgnoreCase(config.defaultServer()))
                    .findFirst();
            defaultServer.ifPresent(event::setInitialServer); // Set destination to default-server if it exists
            if (config.kickOnTimeout()) player.disconnect(mm("<red>You timed out! Select a server."));
            else if (cont != null) cont.resume();                            // resume velocity server placement
        }).delay(config.timeoutSeconds(), TimeUnit.SECONDS);

        ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> {
        }).schedule(); // if postjoin, empty task instead (no dialog timeout)
        if (!playersPostjoin.get(player)) task = taskBuilder.schedule(); // If not postjoin, schedule dialog timeout

        Map<String, Boolean> online = getOnlineServers();

        String brand = "";
        if (player.getClientBrand() != null) brand = player.getClientBrand().split(":")[0];

        Dialog dialog = ServerDialogFactory.build(this, config, listedGroups(), getNonGroupServers(), online, player, brand);
        showDialog(player, dialog);

        playerInitialEvents.put(player, event);
        timeoutTasks.put(player, task);
        if (cont != null) eventContinuations.put(player, cont);
    }

    /**
     * Sends the main server picker dialog to a CommandSource
     *
     * @param source the CommandSource to send the dialog to
     */
    public void sendServersDialog(CommandSource source) {
        if (!(source instanceof Player p)) return;
        begin(new PlayerChooseInitialServerEvent(p, null), null);
    }

    /**
     * Signifies a selection click
     *
     * @param player     the player who clicked
     * @param serverName the name of the server the player clicked on
     */
    public void select(Player player, String serverName) {
        Optional<RegisteredServer> target = listedServers().stream()
                .filter(s -> s.getServerInfo().getName().equalsIgnoreCase(serverName))
                .findFirst();
        if (target.isEmpty()) return;

        ScheduledTask task = timeoutTasks.remove(player);
        if (task != null) task.cancel();

        clearDialog(player);
        showDialog(player, WaitingDialogFactory.build(serverName));

        if (playersPostjoin.get(player)) player.createConnectionRequest(target.get()).connect().thenAccept((result) -> {
            clearDialog(player);
            if (!result.isSuccessful()) {
                showDialog(player,
                        NoticeDialogFactory.build(
                                mm("<red>Could not connect to server: ")
                                        .append(result.getReasonComponent()
                                                .orElse(Component.text("Unknown reason")))));
            }
        });
        else {
            PlayerChooseInitialServerEvent event = playerInitialEvents.remove(player);
            event.setInitialServer(target.get());
        }
        Continuation cont = eventContinuations.remove(player);
        if (cont != null) cont.resume();
    }

    public void group(Player player, String group) {
        List<RegisteredServer> groupServers = getGroupServers(group);

        String brand = "";
        if (player.getClientBrand() != null) brand = player.getClientBrand().split(":")[0];

        clearDialog(player);
        showDialog(player,
                ServerDialogFactory.build(this, config,
                        List.of(), groupServers,
                        getOnlineServers(), player,
                        brand));
    }

    /**
     * Signifies a click on a password-protected server
     *
     * @param player     the player who clicked
     * @param serverName the name of the server the player clicked on
     */
    public void passwd(Player player, String serverName) {
        clearDialog(player);
        showDialog(player, PasswordDialogFactory.build(config, serverName));
    }

    /**
     * Sends a custom dialog to a player, called from the PacketEvents thread
     *
     * @param player the player to send the dialog to
     * @param dialog the dialog to send to the player
     */
    public void custom(Player player, Dialog dialog) {
        showDialog(player, dialog);
    }

    /**
     * Signifies a click on a 'back' button. Sends the player back to the main server picker dialog
     *
     * @param player the player who clicked
     */
    public void back(Player player) {
        clearDialog(player);
        sendServersDialog(player);
    }

    /**
     * Checks the inputted password against the configuration file's password
     *
     * @param server the server in question
     * @param passwd the password inputted by a player
     * @return true if the password matches, false if not
     */
    public boolean checkPasswd(String server, String passwd) {
        Optional<ConfigLoader.Entry> entry = config.servers().stream().filter(e -> e.velocity().equalsIgnoreCase(server)).findFirst();
        return entry.isPresent() && entry.get().pwString().equals(passwd);
    }

    /**
     * Signifies a player clicking the 'disconnect' button. Disconnects the player
     *
     * @param player the player who clicked
     */
    public void quit(Player player) {
        clearDialog(player);

        ScheduledTask task = timeoutTasks.remove(player);
        if (task != null) task.cancel();
        player.disconnect(mm(config.kickQuit()));
        Continuation cont = eventContinuations.remove(player);
        if (cont != null) cont.resume();
    }

    /**
     * Signifies a player clicking the 'close' button. Closes the currently open dialog
     *
     * @param player the player who clicked
     */
    public void close(Player player) {
        clearDialog(player);

        ScheduledTask task = timeoutTasks.remove(player);
        if (task != null) task.cancel();
        Continuation cont = eventContinuations.remove(player);
        if (cont != null) cont.resume();
    }

    /**
     * Called if the player disconnected independently. Cleanup tasks
     *
     * @param player the player who disconnected
     */
    public void abandon(Player player) {
        ScheduledTask task = timeoutTasks.remove(player);
        if (task != null) task.cancel();
        Continuation cont = eventContinuations.remove(player);
        if (cont != null) cont.resume();
    }
}