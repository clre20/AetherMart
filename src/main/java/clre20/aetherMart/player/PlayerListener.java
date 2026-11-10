package clre20.aetherMart.player;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.command.MartCommand;
import clre20.aetherMart.update.UpdateChecker;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

import java.util.UUID;

/** 進服載入、離線清除；ExcellentEconomy 啟用／停用時重新連接。 */
public final class PlayerListener implements Listener {

    private final AetherMart plugin;

    public PlayerListener(AetherMart plugin) {
        this.plugin = plugin;
    }

    public static void loadPlayer(AetherMart plugin, Player p, boolean join) {
        UUID id = p.getUniqueId();
        plugin.links().onJoin(id);
        plugin.players().load(id, d -> {
            Player pl = Bukkit.getPlayer(id);
            if (pl != null && join && (d.banned(System.currentTimeMillis()) || d.hasDebt())) {
                plugin.messages().send(pl, "penalty.join", Vars.of(
                        "until", d.bannedUntil > 0 ? TimeUtil.format(d.bannedUntil, plugin.config().zone, TimeUtil.DATE_TIME_SHORT) : "-",
                        "debt", plugin.guard().debtText(d)));
            }
        });
        plugin.claims().load(id, () -> {
            Player pl = Bukkit.getPlayer(id);
            if (pl != null && join && plugin.claims().count(id) > 0) {
                plugin.claims().remind(pl, 0);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        loadPlayer(plugin, p, true);
        if (plugin.config().updateNotifyJoin && p.hasPermission("aethermart.version")) {
            UUID id = p.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player pl = Bukkit.getPlayer(id);
                if (pl == null) {
                    return;
                }
                plugin.updates().check().thenAccept(r -> Bukkit.getScheduler().runTask(plugin, () -> {
                    Player again = Bukkit.getPlayer(id);
                    if (again != null && r.state() == UpdateChecker.State.OUTDATED) {
                        MartCommand.sendUpdate(plugin, again, r);
                    }
                }));
            }, 60L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        plugin.players().unload(id);
        plugin.claims().unload(id);
        plugin.links().onQuit(id);
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent e) {
        if (e.getPlugin().getName().equals("ExcellentEconomy")) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.currencies().refreshProviders());
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent e) {
        if (e.getPlugin().getName().equals("ExcellentEconomy")) {
            plugin.currencies().refreshProviders();
        }
    }
}
