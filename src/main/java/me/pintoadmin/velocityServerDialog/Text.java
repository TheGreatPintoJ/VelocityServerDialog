package me.pintoadmin.serverSelector;

import net.kyori.adventure.text.*;
import net.kyori.adventure.text.minimessage.*;

final class Text {
    private Text() {}

    static Component mm(String s) {
        return s == null ? Component.empty() : MiniMessage.miniMessage().deserialize(s);
    }
}
