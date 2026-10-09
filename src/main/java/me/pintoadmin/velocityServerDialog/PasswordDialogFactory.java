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

import static me.pintoadmin.velocityServerDialog.Text.mm;

public final class PasswordDialogFactory {
    private static final ResourceLocation PASSWD_SUBMIT = new ResourceLocation(SelectionManager.NAMESPACE, "passwd_submit");
    private static final ResourceLocation BACK_PW = new ResourceLocation(SelectionManager.NAMESPACE, "back_pw");

    private PasswordDialogFactory() {}

    public static Dialog build(ConfigLoader cfg, String name) {
        List<ActionButton> buttons = new ArrayList<>();

        Input textInput = new Input(
                "input_passwd",
                new TextInputControl(
                        200,
                        Component.text("Enter Password"),
                        true,
                        "",
                        256,
                        null
                )
        );

        NBTCompound payload = new NBTCompound();
        payload.setTag("server", new NBTString(name));
        DynamicCustomAction submit = new DynamicCustomAction(PASSWD_SUBMIT, payload);
        ActionButton submitButton = new ActionButton(
                new CommonButtonData(
                        Component.text("Submit"),
                        null,
                        150
                ),
                submit
        );
        buttons.add(submitButton);

        ActionButton back = new ActionButton(
                new CommonButtonData(Component.text("Back", NamedTextColor.RED), null, 200),
                new DynamicCustomAction(BACK_PW, new NBTCompound()));

        CommonDialogData common = new CommonDialogData(
                mm(cfg.title()), null,
                /* canCloseWithEscape */ false,
                /* pause */ false,
                DialogAction.CLOSE,
                List.of(new PlainMessageDialogBody(new PlainMessage(mm(cfg.body()), 300))),
                List.of(textInput));

        return new MultiActionDialog(common, buttons, back, cfg.columns());
    }
}
