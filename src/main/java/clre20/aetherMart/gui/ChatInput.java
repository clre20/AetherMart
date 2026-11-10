package clre20.aetherMart.gui;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.util.Texts;
import clre20.aetherMart.util.Vars;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 聊天室輸入：攔下玩家下一則聊天訊息（不會被廣播），逾時（預設 60 秒）或輸入取消字就返回介面。
 */
public final class ChatInput implements Listener {

    private static final int MAX_LENGTH = 64;

    private record Pending(Consumer<String> onInput, Runnable onCancel, Runnable onQuit, BukkitTask timeout) {
    }

    private final AetherMart plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatInput(AetherMart plugin) {
        this.plugin = plugin;
    }

    public boolean waiting(UUID id) {
        return pending.containsKey(id);
    }

    public void prompt(Player p, String key, Map<String, String> vars, Consumer<String> onInput, Runnable onCancel, Runnable onQuit) {
        cancel(p.getUniqueId(), false);
        int seconds = plugin.config().chatTimeout;
        UUID id = p.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Pending pd = pending.remove(id);
            if (pd != null) {
                Player pl = Bukkit.getPlayer(id);
                if (pl != null) {
                    plugin.messages().send(pl, "input.timeout");
                }
                pd.onCancel().run();
            }
        }, seconds * 20L);
        pending.put(id, new Pending(onInput, onCancel, onQuit, task));
        plugin.messages().send(p, key, vars);
        plugin.messages().send(p, "input.hint", Vars.of("cancel", plugin.config().cancelWord, "seconds", seconds));
    }

    private void cancel(UUID id, boolean runCancel) {
        Pending pd = pending.remove(id);
        if (pd != null) {
            pd.timeout().cancel();
            if (runCancel) {
                pd.onCancel().run();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!pending.containsKey(id)) {
            return;
        }
        e.setCancelled(true);
        e.viewers().clear();
        String raw = Texts.plain(e.message());
        String text = Texts.sanitizeColored(raw, MAX_LENGTH);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Pending pd = pending.remove(id);
            if (pd == null) {
                return;
            }
            pd.timeout().cancel();
            if (text.isEmpty() || text.equalsIgnoreCase(plugin.config().cancelWord) || text.equalsIgnoreCase("cancel")) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    plugin.messages().send(p, "input.cancelled");
                }
                pd.onCancel().run();
                return;
            }
            pd.onInput().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Pending pd = pending.remove(e.getPlayer().getUniqueId());
        if (pd != null) {
            pd.timeout().cancel();
            if (pd.onQuit() != null) {
                pd.onQuit().run();
            }
        }
    }

    /** 插件關閉：等輸入中的玩家視同離線處理（例如把建立拍賣的物品還回去）。 */
    public void shutdown() {
        for (UUID id : pending.keySet()) {
            Pending pd = pending.remove(id);
            if (pd != null) {
                pd.timeout().cancel();
                if (pd.onQuit() != null) {
                    pd.onQuit().run();
                }
            }
        }
    }
}
