package clre20.aetherMart.player;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.util.ItemCodec;
import clre20.aetherMart.util.Vars;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 領取箱：當下給不了的物品與錢都放這裡，物品永遠不會掉在地上。 */
public final class ClaimService {

    public enum Result { OK, PARTIAL, NO_SPACE, UNAVAILABLE, BUSY }

    private final AetherMart plugin;
    private final Map<UUID, List<ClaimEntry>> online = new HashMap<>();

    public ClaimService(AetherMart plugin) {
        this.plugin = plugin;
    }

    public void load(UUID id, Runnable then) {
        plugin.db().submit(c -> read(c, id)).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (Bukkit.getPlayer(id) == null) {
                return;
            }
            online.put(id, list);
            if (then != null) {
                then.run();
            }
        }));
    }

    public void unload(UUID id) {
        online.remove(id);
    }

    public void clear() {
        online.clear();
    }

    /** null = 尚未載入。 */
    public List<ClaimEntry> list(UUID id) {
        return online.get(id);
    }

    public int count(UUID id) {
        List<ClaimEntry> l = online.get(id);
        return l == null ? 0 : l.size();
    }

    private List<ClaimEntry> read(Connection c, UUID owner) throws SQLException {
        List<ClaimEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, kind, items, currency, amount, source, created FROM claims WHERE owner=? ORDER BY id")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        ClaimEntry.Kind kind = ClaimEntry.Kind.valueOf(rs.getString(2));
                        List<ItemStack> items = kind == ClaimEntry.Kind.ITEMS ? ItemCodec.decodeList(rs.getBytes(3)) : List.of();
                        BigDecimal amount = rs.getString(5) == null ? null : new BigDecimal(rs.getString(5));
                        ClaimEntry.Source src;
                        try {
                            src = ClaimEntry.Source.valueOf(rs.getString(6));
                        } catch (IllegalArgumentException e) {
                            src = ClaimEntry.Source.RETURNED;
                        }
                        out.add(new ClaimEntry(rs.getLong(1), owner, kind, new ArrayList<>(items), rs.getString(4), amount, src, rs.getLong(7)));
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Skipped unreadable claim #" + rs.getLong(1) + ": " + e.getMessage());
                    }
                }
            }
        }
        return out;
    }

    /** 加入領取箱（寫入資料庫後，線上玩家的清單同步更新並提醒）。 */
    public CompletableFuture<Void> add(ClaimEntry e) {
        byte[] blob = e.kind == ClaimEntry.Kind.ITEMS ? ItemCodec.encodeList(e.items) : null;
        CompletableFuture<Void> done = new CompletableFuture<>();
        plugin.db().submit(c -> insert(c, e, blob)).whenComplete((id, err) -> {
            if (err != null) {
                plugin.activity().log("ERROR", "claim insert failed for " + e.owner + ": " + err);
                done.complete(null);
                return;
            }
            e.id = id;
            if (!plugin.isEnabled()) {
                done.complete(null);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                List<ClaimEntry> l = online.get(e.owner);
                if (l != null) {
                    l.add(e);
                }
                Player p = Bukkit.getPlayer(e.owner);
                if (p != null) {
                    remind(p, 1);
                }
                done.complete(null);
            });
        });
        plugin.activity().log("CLAIM", "+ " + e.owner + " " + e.source + " " + describe(e));
        return done;
    }

    private static long insert(Connection c, ClaimEntry e, byte[] blob) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO claims(owner,kind,items,currency,amount,source,created) VALUES(?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, e.owner.toString());
            ps.setString(2, e.kind.name());
            ps.setBytes(3, blob);
            ps.setString(4, e.currency);
            ps.setString(5, e.amount == null ? null : e.amount.toPlainString());
            ps.setString(6, e.source.name());
            ps.setLong(7, e.created);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public void remind(Player p, int added) {
        plugin.messages().send(p, added > 0 ? "claim.new" : "claim.reminder", Vars.of("count", count(p.getUniqueId())));
        p.sendMessage(plugin.messages().get("claim.open-link", Vars.of())
                .clickEvent(ClickEvent.runCommand("/aethermart claim")));
    }

    /** 領取一筆；在主執行緒呼叫。 */
    public Result claim(Player p, ClaimEntry e) {
        List<ClaimEntry> l = online.get(p.getUniqueId());
        if (l == null || !l.contains(e) || e.busy || e.id == 0) {
            return Result.BUSY;
        }
        if (e.kind == ClaimEntry.Kind.MONEY) {
            Currency cur = plugin.currencies().get(e.currency);
            if (cur == null || !cur.available() || !cur.provider().deposit(p, e.amount)) {
                return Result.UNAVAILABLE;
            }
            l.remove(e);
            delete(e.id);
            plugin.activity().log("CLAIM", "- " + p.getName() + " money " + cur.format(e.amount));
            return Result.OK;
        }
        Map<Integer, ItemStack> left = p.getInventory().addItem(e.items.stream().map(ItemStack::clone).toArray(ItemStack[]::new));
        if (left.isEmpty()) {
            l.remove(e);
            delete(e.id);
            plugin.activity().log("CLAIM", "- " + p.getName() + " " + describe(e));
            return Result.OK;
        }
        List<ItemStack> remaining = new ArrayList<>(left.values());
        int before = total(e.items);
        if (total(remaining) == before) {
            return Result.NO_SPACE;
        }
        e.items = remaining;
        byte[] blob = ItemCodec.encodeList(remaining);
        long id = e.id;
        plugin.db().execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE claims SET items=? WHERE id=?")) {
                ps.setBytes(1, blob);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        });
        plugin.activity().log("CLAIM", "~ " + p.getName() + " partial, left " + describe(e));
        return Result.PARTIAL;
    }

    private void delete(long id) {
        plugin.db().execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM claims WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        });
    }

    private static int total(Collection<ItemStack> items) {
        int n = 0;
        for (ItemStack i : items) {
            n += i.getAmount();
        }
        return n;
    }

    public String describe(ClaimEntry e) {
        if (e.kind == ClaimEntry.Kind.MONEY) {
            Currency c = plugin.currencies().get(e.currency);
            return c == null ? e.amount + " " + e.currency : c.format(e.amount);
        }
        StringBuilder sb = new StringBuilder();
        for (ItemStack i : e.items) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(plugin.names().name(i)).append(" x").append(i.getAmount());
            if (sb.length() > 300) {
                sb.append("…");
                break;
            }
        }
        return sb.toString();
    }
}
