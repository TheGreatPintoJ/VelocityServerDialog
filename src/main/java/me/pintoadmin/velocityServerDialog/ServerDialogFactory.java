package me.pintoadmin.velocityServerDialog;

import com.github.retrooper.packetevents.protocol.dialog.*;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.dialog.action.*;
import com.github.retrooper.packetevents.protocol.dialog.body.*;
import com.github.retrooper.packetevents.protocol.dialog.button.*;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.resources.*;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;

import java.io.*;
import java.util.*;
import java.util.List;

import static me.pintoadmin.velocityServerDialog.Text.*;

public final class ServerDialogFactory {
    private static final ResourceLocation SELECT = new ResourceLocation(SelectionManager.NAMESPACE, "select");
    private static final ResourceLocation GROUP = new ResourceLocation(SelectionManager.NAMESPACE, "group");
    private static final ResourceLocation PASSWD = new ResourceLocation(SelectionManager.NAMESPACE, "passwd");
    private static final ResourceLocation LOCKED = new ResourceLocation(SelectionManager.NAMESPACE, "locked");
    private static final ResourceLocation MAINTENANCE = new ResourceLocation(SelectionManager.NAMESPACE, "maintenance");
    private static final ResourceLocation QUIT   = new ResourceLocation(SelectionManager.NAMESPACE, "quit");
    private static final ResourceLocation CLOSE   = new ResourceLocation(SelectionManager.NAMESPACE, "close");

    private ServerDialogFactory() {}

    /**
     * Builds an instance of the main server picker dialog
     * @param manager the SelectionManager instance to handle selections
     * @param cfg the ConfigLoader instance to load info from
     * @param servers the servers registered in config file
     * @param online the map of server to online state
     * @param player the player to create the dialog for
     * @param clientType the client brand of the player in question
     * @return the built MultiActionDialog
     */
    public static Dialog build(SelectionManager manager,
                               ConfigLoader cfg,
                               List<String> groups,
                               List<RegisteredServer> servers,
                               Map<String, Boolean> online,
                               Player player, String clientType) {
        List<ActionButton> buttons = new ArrayList<>();
        try {
            cfg = cfg.reload();
        } catch (IOException e) {
            e.printStackTrace();
        }
        for (String id : groups) {
            ConfigLoader.Group group = cfg.group(id);

            Component label = mm(group.name());
            Component tooltip = group.hover().isEmpty() ? null : mm(group.hover());

            NBTCompound payload = new NBTCompound();
            payload.setTag("group", new NBTString(id));

            CommonButtonData data = new CommonButtonData(label, tooltip, 200);
            DynamicCustomAction groupAction = new DynamicCustomAction(GROUP, payload);

            ActionButton button = new ActionButton(data, groupAction);
//            String showPerm = group.showPermString();
//            if(showPerm.isEmpty() || player.hasPermission(showPerm))
            buttons.add(button);
        }
        for (RegisteredServer server : servers) {
            String name = server.getServerInfo().getName();
            ConfigLoader.Entry entry = cfg.entry(name); // falls back to name/""
            boolean up = online.getOrDefault(name, false);

            boolean serverLocked = false;
            String serverPasswd = entry.pwString();
            String serverPerm = entry.permString();

            boolean serverMaintenance = entry.maintenance();

            if(!serverPerm.isBlank() && !player.hasPermission(serverPerm))
                serverLocked = true;

            if(!Objects.equals(entry.type(), "vanilla")) { // non-vanilla server
                if(clientType.contains("vanilla")){ // vanilla client
                    serverLocked = true; // prevent joining
                }
            }

            Component label;
            if (serverLocked) {
                label = mm(entry.display()).color(TextColor.color(255, 50, 50))
                        .append(Component.text(" (locked)", NamedTextColor.DARK_RED))
                        .decorate(TextDecoration.BOLD);
            } else if(serverMaintenance) {
                label = mm(entry.display()).color(TextColor.color(175, 0, 255))
                        .append(Component.text(" (maintenance)", NamedTextColor.DARK_PURPLE))
                        .decorate(TextDecoration.BOLD);
            } else {
                label = mm(entry.display()).append(up
                        ? Component.text(" (" + server.getPlayersConnected().size() + " online)", NamedTextColor.GRAY)
                        : Component.text(" (offline)", NamedTextColor.DARK_GRAY));
            }

            Component tooltip = entry.description().isEmpty() ? null : mm(entry.description());

            NBTCompound payload = new NBTCompound();
            payload.setTag("server", new NBTString(name));

            CommonButtonData data = new CommonButtonData(label, tooltip, 200);
            DynamicCustomAction action = new DynamicCustomAction(SELECT, payload);
            DynamicCustomAction passwd = new DynamicCustomAction(PASSWD, payload);
            DynamicCustomAction locked = new DynamicCustomAction(LOCKED, payload);
            DynamicCustomAction maintenance = new DynamicCustomAction(MAINTENANCE, payload);

            Action finalAction = serverMaintenance && !player.hasPermission(cfg.maintenancePerm()) ? maintenance : serverLocked ? locked : !serverPasswd.isBlank() ? passwd : action;
            ActionButton button = new ActionButton(data, finalAction);
            String showPerm = entry.showPermString();
            if(showPerm.isEmpty() || player.hasPermission(showPerm))
                buttons.add(button);
        }

        ActionButton quit = new ActionButton(
                new CommonButtonData(Component.text(manager.playersPostjoin.get(player) ? "Close" : "Disconnect", NamedTextColor.RED), null, 200),
                new DynamicCustomAction(manager.playersPostjoin.get(player) ? CLOSE : QUIT, new NBTCompound()));

        CommonDialogData common = new CommonDialogData(
                mm(cfg.title()), null,
                /* canCloseWithEscape */ manager.playersPostjoin.get(player),
                /* pause */ false,
                DialogAction.CLOSE,
                List.of(new PlainMessageDialogBody(new PlainMessage(mm(cfg.body()), 300))),
                List.of());

        return new MultiActionDialog(common, buttons, quit, cfg.columns());
    }
}
