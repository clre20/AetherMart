package clre20.aetherMart.discord;

import clre20.aetherMart.AetherMart;
import clre20.discordLink.api.DiscordLinkAPI;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 讀取 DiscordLink 的綁定資料。
 * 優先透過 DiscordLinkAPI 記憶體快取查詢（不碰硬碟、不開連線線程）。
 */
public final class LinkReader {

    private final AetherMart plugin;
    private final Set<UUID> bypass = ConcurrentHashMap.newKeySet();

    public LinkReader(AetherMart plugin) {
        this.plugin = plugin;
    }

    public boolean available() {
        return DiscordLinkAPI.isAvailable();
    }

    /** 主執行緒：能否交易（依 config 的 require-link）。 */
    public boolean allowed(Player p, boolean buying) {
        boolean required = buying ? plugin.config().requireLinkBuy : plugin.config().requireLinkSell;
        if (!required) {
            return true;
        }
        if (!available()) {
            return false;
        }
        UUID id = p.getUniqueId();
        return isLinked(id) || (plugin.config().allowBypass && bypass.contains(id));
    }

    public boolean isLinked(UUID id) {
        return DiscordLinkAPI.isAvailable() && DiscordLinkAPI.isLinked(plugin, id);
    }

    public boolean known(UUID id) {
        return isLinked(id);
    }

    public void start() {
        // 由 DiscordLinkAPI 即時查詢記憶體，不再需要背景定期讀取硬碟
    }

    public void onJoin(UUID id) {
        // 即時查詢，無需背景排隊
    }

    public void refresh(UUID id) {
        // 即時查詢，無需背景排隊
    }

    public void refresh(UUID id, long gapMs) {
        // 即時查詢，無需背景排隊
    }

    public void watchUnlinked(Player p) {
        // 即時查詢
    }

    public void onQuit(UUID id) {
        bypass.remove(id);
    }

    public Optional<String> discordId(UUID id) {
        if (!DiscordLinkAPI.isAvailable()) {
            return Optional.empty();
        }
        return DiscordLinkAPI.getDiscordId(plugin, id);
    }

    public Optional<String> get(UUID id) {
        return discordId(id);
    }

    public void close() {
        // 無硬碟連線需關閉
    }

    public void shutdown() {
        // 無執行緒需關閉
    }
}
