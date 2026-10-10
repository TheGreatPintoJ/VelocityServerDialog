package me.pintoadmin.velocityServerDialog;

import com.github.retrooper.packetevents.*;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.packettype.*;
import com.github.retrooper.packetevents.protocol.player.*;
import com.github.retrooper.packetevents.protocol.sound.*;
import com.github.retrooper.packetevents.resources.*;
import com.github.retrooper.packetevents.util.*;
import com.github.retrooper.packetevents.wrapper.configuration.client.*;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.velocitypowered.api.proxy.*;

import java.awt.*;
import java.sql.*;
import java.util.*;

public final class ClickPacketListener implements PacketListener {
    private final ProxyServer proxy;
    private final SelectionManager manager;
    private final DBManager dbManager;

    public ClickPacketListener(ProxyServer proxy, SelectionManager manager, DBManager dbManager) {
        this.proxy = proxy;
        this.manager = manager;
        this.dbManager = dbManager;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Configuration.Client.CUSTOM_CLICK_ACTION || event.getPacketType() == PacketType.Play.Client.CUSTOM_CLICK_ACTION) {
            boolean postjoin = event.getPacketType() == PacketType.Play.Client.CUSTOM_CLICK_ACTION;
            manager.playersPostjoin.put(event.getPlayer(), postjoin);

            var packet = /*postjoin ? new WrapperPlayClientCustomClickAction(event) : */new WrapperConfigClientCustomClickAction(event);
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
                case "locked" -> {
                    if (packet.getPayload() instanceof NBTCompound tag) {
                        String server = tag.getStringTagValueOrNull("server");
                        if (server != null) manager.custom(uuid, NoticeDialogFactory.build("<red>The "+server+" server is locked"));
                    }
                }
                case "maintenance" -> {
                    if (packet.getPayload() instanceof NBTCompound tag){
                        String server = tag.getStringTagValueOrNull("server");
                        if (server != null) manager.custom(uuid, NoticeDialogFactory.build("<dark_purple>The "+server+" server is under maintenance"));
                    }
                }
                case "passwd" -> {
                    if (packet.getPayload() instanceof NBTCompound tag) {
                        String server = tag.getStringTagValueOrNull("server");
                        if (server != null)
                            try {
                                String savedPasswd = dbManager.getSavedPassword(uuid, server);
                                if(savedPasswd != null && manager.checkPasswd(server, savedPasswd)){
                                    manager.select(uuid, server);
                                } else throw new SQLException();
                            } catch (SQLException e) {
                               manager.passwd(uuid, server);
                            }
                    }
                }
                case "passwd_submit" -> {
                    if (packet.getPayload() instanceof NBTCompound tag) {
                        String server = tag.getStringTagValueOrNull("server");
                        String passwd = tag.getStringTagValueOrNull("input_passwd");

                        if (manager.checkPasswd(server, passwd)) {
                            try {
                                dbManager.addSavedPassword(uuid, server, passwd);
                            } catch (SQLException e) {
                                e.printStackTrace();
                            }
                            manager.select(uuid, server);
                        }
                    }
                }
                case "back" -> manager.back(uuid);
                case "close" -> manager.close(uuid);
                case "quit" -> manager.quit(uuid);
                default -> {
                }
            }
        }
    }
}