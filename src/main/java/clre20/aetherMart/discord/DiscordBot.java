package clre20.aetherMart.discord;

import clre20.discordLink.api.DiscordLinkAPI;
import net.dv8tion.jda.api.JDA;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * AetherMart Discord 機器人通道：
 * 全面改由共用 DiscordLink 對外開放的 Bot API，不再自行打包 JDA 或開啟背景連線線程。
 */
public final class DiscordBot {

    private final Logger logger;

    public DiscordBot(Logger logger) {
        this.logger = logger;
    }

    public boolean ready() {
        return DiscordLinkAPI.isAvailable() && DiscordLinkAPI.isBotReady();
    }

    public JDA jda() {
        if (!DiscordLinkAPI.isAvailable()) {
            return null;
        }
        Plugin p = Bukkit.getPluginManager().getPlugin("AetherMart");
        return DiscordLinkAPI.getJda(p);
    }

    public boolean configured() {
        return DiscordLinkAPI.isAvailable();
    }

    public void apply(String newToken) {
        // 不需要個別 Token，由 DiscordLink 統一管理
    }

    public void shutdown() {
        // 無常駐背景連線線程，由 DiscordLink 統籌生命週期
    }
}
