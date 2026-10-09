package me.pintoadmin.velocityServerDialog;

import com.github.retrooper.packetevents.protocol.dialog.*;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.dialog.action.*;
import com.github.retrooper.packetevents.protocol.dialog.body.*;
import com.github.retrooper.packetevents.protocol.dialog.button.*;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.resources.*;
import com.velocitypowered.api.proxy.server.*;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;

import java.awt.*;
import java.util.*;
import java.util.List;

import static me.pintoadmin.velocityServerDialog.Text.*;

public final class DialogFactory {
    private static final ResourceLocation SELECT = new ResourceLocation(SelectionManager.NAMESPACE, "select");
    private static final ResourceLocation QUIT   = new ResourceLocation(SelectionManager.NAMESPACE, "quit");

    private DialogFactory() {}

    public static Dialog build(ConfigLoader cfg, List<RegisteredServer> servers, Map<String, Boolean> online, String clientType) {
        List<ActionButton> buttons = new ArrayList<>();
        for (RegisteredServer server : servers) {
            String name = server.getServerInfo().getName();
            ConfigLoader.Entry entry = cfg.entry(name); // falls back to name/""
            boolean up = online.getOrDefault(name, false);

            boolean serverLocked = false;

            if(!Objects.equals(entry.type(), "vanilla")) { // non-vanilla server
                if(clientType.contains("vanilla")){ // vanilla client
                    serverLocked = true; // prevent joining
                }
            }

            Component label;
            if(!serverLocked) {
                label = mm(entry.display()).append(up
                        ? Component.text(" (" + server.getPlayersConnected().size() + " online)", NamedTextColor.GRAY)
                        : Component.text(" (offline)", NamedTextColor.DARK_GRAY));
            } else {
                label = mm(entry.display()).color(TextColor.color(255, 50, 50))
                        .append(Component.text(" (locked)", NamedTextColor.DARK_RED))
                        .decorate(TextDecoration.BOLD);
            }

            Component tooltip = entry.description().isEmpty() ? null : mm(entry.description());

            NBTCompound payload = new NBTCompound();
            payload.setTag("server", new NBTString(name));

            CommonButtonData data = new CommonButtonData(label, tooltip, 200);
            DynamicCustomAction action = new DynamicCustomAction(SELECT, payload);
            ActionButton button = new ActionButton(data, serverLocked ? null : action);

            buttons.add(button);
        }

        ActionButton quit = new ActionButton(
                new CommonButtonData(Component.text("Disconnect", NamedTextColor.RED), null, 200),
                new DynamicCustomAction(QUIT, new NBTCompound()));

        CommonDialogData common = new CommonDialogData(
                mm(cfg.title()), null,
                /* canCloseWithEscape */ false,
                /* pause */ false,
                DialogAction.WAIT_FOR_RESPONSE,
                List.of(new PlainMessageDialogBody(new PlainMessage(mm(cfg.body()), 300))),
                List.of());

        return new MultiActionDialog(common, buttons, quit, cfg.columns());
    }
}
