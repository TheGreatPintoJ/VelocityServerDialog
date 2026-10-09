package me.pintoadmin.velocityServerDialog;

import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.packettype.*;
import com.github.retrooper.packetevents.resources.*;
import com.github.retrooper.packetevents.wrapper.configuration.client.*;

import java.util.*;

public final class ClickPacketListener implements PacketListener {
    private final SelectionManager manager;

    public ClickPacketListener(SelectionManager manager) {
        this.manager = manager;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Configuration.Client.CUSTOM_CLICK_ACTION) return;

        var packet = new WrapperConfigClientCustomClickAction(event);
        ResourceLocation id = packet.getId();
        if (!SelectionManager.NAMESPACE.equals(id.getNamespace())) return;

        event.setCancelled(true); // Velocity never sees it (it would drop it anyway with no backend)
        UUID uuid = event.getUser().getUUID();
        if (uuid == null) return;

        switch (id.getKey()) {
            case "select" -> {
                if (packet.getPayload() instanceof NBTCompound tag) {
                    String server = tag.getStringTagValueOrNull("server");
                    if (server != null) manager.select(uuid, server);
                }
            }
            case "passwd" -> {
                if (packet.getPayload() instanceof NBTCompound tag) {
                    String server = tag.getStringTagValueOrNull("server");
                    if (server != null) manager.passwd(uuid, server);
                }
            }
            case "passwd_submit" -> {
                if (packet.getPayload() instanceof NBTCompound tag) {
                    String server = tag.getStringTagValueOrNull("server");
                    String passwd = tag.getStringTagValueOrNull("input_passwd");
                    System.out.println("Server: "+server+" - "+passwd);
                    if (manager.checkPasswd(server, passwd)){
                        manager.select(uuid, server);
                    }
                }
            }
            case "back_pw" -> manager.passwd_back(uuid);
            case "quit" -> manager.quit(uuid);
            default -> { }
        }
    }
}
