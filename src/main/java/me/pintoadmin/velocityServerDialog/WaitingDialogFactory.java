package me.pintoadmin.velocityServerDialog;

import com.github.retrooper.packetevents.protocol.dialog.*;
import com.github.retrooper.packetevents.protocol.dialog.action.*;
import com.github.retrooper.packetevents.protocol.dialog.body.*;
import com.github.retrooper.packetevents.protocol.dialog.button.*;
import com.github.retrooper.packetevents.protocol.dialog.input.*;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.resources.*;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.format.*;

import java.util.*;

import static me.pintoadmin.velocityServerDialog.Text.*;

public final class WaitingDialogFactory {
    private static final ResourceLocation QUIT   = new ResourceLocation(SelectionManager.NAMESPACE, "quit");

    private WaitingDialogFactory() {}

    public static Dialog build(String name) {
        ActionButton back = new ActionButton(
                new CommonButtonData(Component.text("Disconnect", NamedTextColor.RED), null, 200),
                new DynamicCustomAction(QUIT, new NBTCompound()));

        CommonDialogData common = new CommonDialogData(
                mm("<green>Connecting to <gold><bold>"+name+"</bold></gold><green>..."), null,
                /* canCloseWithEscape */ false,
                /* pause */ false,
                DialogAction.CLOSE,
                List.of(new PlainMessageDialogBody(new PlainMessage(mm(""), 300))),
                List.of());

        return new NoticeDialog(common, back);
    }
}
