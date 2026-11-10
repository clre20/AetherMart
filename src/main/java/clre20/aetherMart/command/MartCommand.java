package clre20.aetherMart.command;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.data.TradeRecord;
import clre20.aetherMart.gui.HubMenu;
import clre20.aetherMart.gui.admin.AdminAuctionsMenu;
import clre20.aetherMart.gui.admin.AdminCategoriesMenu;
import clre20.aetherMart.gui.admin.AdminHomeMenu;
import clre20.aetherMart.gui.auction.ClaimBoxMenu;
import clre20.aetherMart.gui.auction.HistoryMenu;
import clre20.aetherMart.update.UpdateChecker;
import clre20.aetherMart.util.Texts;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /aethermart（別名 /mart）。玩家功能全部從介面進入。 */
public final class MartCommand implements TabExecutor {

    private final AetherMart plugin;

    public MartCommand(AetherMart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                help(sender, label);
                return true;
            }
            if (!perm(sender, "aethermart.use")) {
                return true;
            }
            new HubMenu(plugin, p).open();
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> {
                if (perm(sender, "aethermart.help")) {
                    help(sender, label);
                }
            }
            case "version" -> {
                if (perm(sender, "aethermart.version")) {
                    version(sender);
                }
            }
            case "reload" -> {
                if (perm(sender, "aethermart.reload")) {
                    plugin.reload();
                    plugin.messages().send(sender, "command.reloaded");
                    plugin.activity().log("ADMIN", sender.getName() + " reloaded AetherMart");
                }
            }
            case "claim" -> {
                if (sender instanceof Player p && perm(sender, "aethermart.use")) {
                    new ClaimBoxMenu(plugin, p, 0).open();
                }
            }
            case "admin" -> admin(sender, args);
            default -> plugin.messages().send(sender, "command.unknown", Vars.of("label", label));
        }
        return true;
    }

    private boolean perm(CommandSender s, String perm) {
        if (s.hasPermission(perm)) {
            return true;
        }
        plugin.messages().send(s, "common.no-permission");
        return false;
    }

    private void admin(CommandSender sender, String[] args) {
        String what = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (what) {
            case "" -> {
                if (!perm(sender, "aethermart.admin.use")) {
                    return;
                }
                if (sender instanceof Player p) {
                    new AdminHomeMenu(plugin, p).open();
                } else {
                    plugin.messages().send(sender, "command.player-only");
                }
            }
            case "auction" -> {
                if (!perm(sender, "aethermart.admin.auction")) {
                    return;
                }
                if (sender instanceof Player p) {
                    new AdminAuctionsMenu(plugin, p, 0).open();
                } else {
                    plugin.messages().send(sender, "command.player-only");
                }
            }
            case "shop" -> {
                if (!perm(sender, "aethermart.admin.shop")) {
                    return;
                }
                if (sender instanceof Player p) {
                    new AdminCategoriesMenu(plugin, p, 0).open();
                } else {
                    plugin.messages().send(sender, "command.player-only");
                }
            }
            case "log" -> {
                if (!perm(sender, "aethermart.admin.log")) {
                    return;
                }
                UUID target = null;
                String name = null;
                if (args.length >= 3) {
                    name = Texts.sanitizeInput(args[2], 16);
                    OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
                    if (op == null) {
                        plugin.messages().send(sender, "common.unknown-player", Vars.of("player", name));
                        return;
                    }
                    target = op.getUniqueId();
                    name = op.getName() == null ? name : op.getName();
                }
                if (sender instanceof Player p) {
                    new HistoryMenu(plugin, p, true, target, name, 0).open();
                } else {
                    consoleLog(sender, target, args.length >= 4 ? parsePage(args[3]) : 0);
                }
            }
            default -> plugin.messages().send(sender, "command.unknown", Vars.of("label", "aethermart"));
        }
    }

    private static int parsePage(String s) {
        try {
            return Math.max(0, Math.min(10_000, Integer.parseInt(s) - 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void consoleLog(CommandSender sender, UUID target, int page) {
        plugin.trades().query(target, null, page * 20, 20).whenComplete((list, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                plugin.messages().send(sender, "command.log-failed");
                return;
            }
            plugin.messages().sendRaw(sender, "command.log-header", Vars.of("page", page + 1, "count", list.size()));
            for (TradeRecord r : list) {
                Currency c = plugin.currencies().get(r.currency());
                String amount;
                try {
                    amount = c == null ? r.amount() : c.format(new BigDecimal(r.amount()));
                } catch (RuntimeException e) {
                    amount = r.amount();
                }
                plugin.messages().sendRaw(sender, "command.log-line", Vars.of(
                        "time", TimeUtil.format(r.time(), plugin.config().zone, TimeUtil.DATE_TIME),
                        "type", plugin.messages().plain("trade.type." + r.type().name().toLowerCase(), Map.of()),
                        "actor", r.actorName() == null ? "-" : r.actorName(), "other", r.otherName() == null ? "-" : r.otherName(),
                        "amount", amount == null ? "-" : amount, "items", r.summary() == null ? "" : r.summary()));
            }
        }));
    }

    private void help(CommandSender s, String label) {
        plugin.messages().sendRaw(s, "command.help.header", Vars.of("version", plugin.getPluginMeta().getVersion()));
        String[][] rows = {
                {"aethermart.use", "command.help.open"},
                {"aethermart.help", "command.help.help"},
                {"aethermart.version", "command.help.version"},
                {"aethermart.reload", "command.help.reload"},
                {"aethermart.admin.use", "command.help.admin"},
                {"aethermart.admin.auction", "command.help.admin-auction"},
                {"aethermart.admin.shop", "command.help.admin-shop"},
                {"aethermart.admin.log", "command.help.admin-log"},
        };
        for (String[] r : rows) {
            if (s.hasPermission(r[0])) {
                plugin.messages().sendRaw(s, r[1], Vars.of("label", label));
            }
        }
    }

    private void version(CommandSender sender) {
        plugin.messages().send(sender, "update.checking", Vars.of("version", plugin.getPluginMeta().getVersion()));
        plugin.updates().check().whenComplete((r, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || r == null) {
                plugin.messages().send(sender, "update.failed", Vars.of("error", err == null ? "?" : err.getClass().getSimpleName()));
                return;
            }
            sendUpdate(plugin, sender, r);
        }));
    }

    public static void sendUpdate(AetherMart plugin, CommandSender s, UpdateChecker.Result r) {
        Map<String, String> v = Vars.of("current", r.current(), "latest", r.latest() == null ? "" : r.latest(),
                "url", r.url() == null ? "" : r.url(), "error", r.error() == null ? "" : r.error());
        switch (r.state()) {
            case LATEST -> plugin.messages().send(s, "update.latest", v);
            case OUTDATED -> plugin.messages().send(s, "update.outdated", v);
            case DEVELOPMENT -> plugin.messages().send(s, "update.development", v);
            case NO_RELEASE -> plugin.messages().send(s, "update.no-release", v);
            case FAILED -> plugin.messages().send(s, "update.failed", v);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            add(out, sender, "aethermart.help", "help", args[0]);
            add(out, sender, "aethermart.version", "version", args[0]);
            add(out, sender, "aethermart.reload", "reload", args[0]);
            if (sender.hasPermission("aethermart.admin.use") || sender.hasPermission("aethermart.admin.auction")
                    || sender.hasPermission("aethermart.admin.shop") || sender.hasPermission("aethermart.admin.log")) {
                if ("admin".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add("admin");
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            add(out, sender, "aethermart.admin.auction", "auction", args[1]);
            add(out, sender, "aethermart.admin.shop", "shop", args[1]);
            add(out, sender, "aethermart.admin.log", "log", args[1]);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("log")
                && sender.hasPermission("aethermart.admin.log")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(p.getName());
                }
                if (out.size() >= 50) {
                    break;
                }
            }
        }
        return out;
    }

    private static void add(List<String> out, CommandSender s, String perm, String value, String typed) {
        if (s.hasPermission(perm) && value.startsWith(typed.toLowerCase(Locale.ROOT))) {
            out.add(value);
        }
    }
}
