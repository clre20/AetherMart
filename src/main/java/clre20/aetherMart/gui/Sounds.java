package clre20.aetherMart.gui;

import clre20.aetherMart.AetherMart;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** 介面音效（config sounds.enabled 可關）。 */
public final class Sounds {

    private final AetherMart plugin;

    public Sounds(AetherMart plugin) {
        this.plugin = plugin;
    }

    public void click(Player p) {
        play(p, Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    public void success(Player p) {
        play(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.1f);
    }

    public void fail(Player p) {
        play(p, Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
    }

    private void play(Player p, Sound s, float volume, float pitch) {
        if (plugin.config().sounds && p != null && p.isOnline()) {
            p.playSound(p.getLocation(), s, volume, pitch);
        }
    }
}
