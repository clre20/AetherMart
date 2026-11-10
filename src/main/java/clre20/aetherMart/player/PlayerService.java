package clre20.aetherMart.player;

import clre20.aetherMart.data.Database;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 玩家資料：線上玩家放記憶體（進服時在背景載入，離線清除），修改時寫入資料庫。 */
public final class PlayerService {

    public record PenaltyRow(UUID player, String name, long until, String reason, Map<String, BigDecimal> debts) {
    }

    private final Plugin plugin;
    private final Database db;
    private final Map<UUID, PlayerData> online = new HashMap<>();

    public PlayerService(Plugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public PlayerData get(UUID id) {
        return online.get(id);
    }

    public void load(UUID id, Consumer<PlayerData> then) {
        db.submit(c -> read(c, id)).thenAccept(data -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (Bukkit.getPlayer(id) == null) {
                return;
            }
            online.put(id, data);
            if (then != null) {
                then.accept(data);
            }
        }));
    }

    public void unload(UUID id) {
        online.remove(id);
    }

    public void clear() {
        online.clear();
    }

    private static PlayerData read(Connection c, UUID id) throws SQLException {
        PlayerData d = new PlayerData();
        String u = id.toString();
        try (PreparedStatement ps = c.prepareStatement("SELECT flags FROM settings WHERE player=?")) {
            ps.setString(1, u);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    d.disabledNotify = rs.getInt(1);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT until, reason FROM penalties WHERE player=?")) {
            ps.setString(1, u);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    d.bannedUntil = rs.getLong(1);
                    d.banReason = rs.getString(2);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT currency, amount FROM debts WHERE player=?")) {
            ps.setString(1, u);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal v = parse(rs.getString(2));
                    if (v.signum() > 0) {
                        d.debts.put(rs.getString(1), v);
                    }
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT product, period, count FROM purchase_counts WHERE player=?")) {
            ps.setString(1, u);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    d.purchases.put(rs.getString(1) + "|" + rs.getString(2), rs.getInt(3));
                }
            }
        }
        return d;
    }

    static BigDecimal parse(String s) {
        try {
            return new BigDecimal(s);
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    // ---------------- 通知設定 ----------------

    public void setNotify(UUID id, int flag, boolean enabled) {
        PlayerData d = online.get(id);
        if (d == null) {
            return;
        }
        d.disabledNotify = enabled ? d.disabledNotify & ~flag : d.disabledNotify | flag;
        int flags = d.disabledNotify;
        db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO settings(player,flags) VALUES(?,?) ON CONFLICT(player) DO UPDATE SET flags=excluded.flags")) {
                ps.setString(1, id.toString());
                ps.setInt(2, flags);
                ps.executeUpdate();
            }
        });
    }

    /** 讀取（含離線玩家）的通知設定；在資料庫執行緒完成。 */
    public CompletableFuture<Integer> disabledNotify(UUID id) {
        PlayerData d = online.get(id);
        if (d != null) {
            return CompletableFuture.completedFuture(d.disabledNotify);
        }
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT flags FROM settings WHERE player=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** 從資料庫讀通知設定（任何執行緒都可呼叫；設定的寫入在同一條資料庫執行緒排在前面，所以讀到的是最新值）。 */
    public CompletableFuture<Integer> disabledNotifyDb(UUID id) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT flags FROM settings WHERE player=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    // ---------------- 購買次數 ----------------

    public int purchased(PlayerData d, String product, String period) {
        return d.purchases.getOrDefault(product + "|" + period, 0);
    }

    public void addPurchase(UUID id, String product, String period, int amount) {
        PlayerData d = online.get(id);
        if (d != null) {
            d.purchases.merge(product + "|" + period, amount, Integer::sum);
        }
        db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO purchase_counts(player,product,period,count) VALUES(?,?,?,?) ON CONFLICT(player,product,period) DO UPDATE SET count=count+excluded.count")) {
                ps.setString(1, id.toString());
                ps.setString(2, product);
                ps.setString(3, period);
                ps.setInt(4, amount);
                ps.executeUpdate();
            }
        });
    }

    /** 刪除已過期的購買次數（保留目前期間與累計）。 */
    public void cleanupPurchases(java.util.Set<String> currentPeriods) {
        List<String> keep = new ArrayList<>(currentPeriods);
        keep.add("T");
        db.execute(c -> {
            StringBuilder sql = new StringBuilder("DELETE FROM purchase_counts WHERE period NOT IN (");
            for (int i = 0; i < keep.size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
            }
            sql.append(")");
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < keep.size(); i++) {
                    ps.setString(i + 1, keep.get(i));
                }
                ps.executeUpdate();
            }
        });
        for (PlayerData d : online.values()) {
            d.purchases.keySet().removeIf(k -> {
                String period = k.substring(k.indexOf('|') + 1);
                return !keep.contains(period);
            });
        }
    }

    public void removeProductCounts(String product) {
        for (PlayerData d : online.values()) {
            d.purchases.keySet().removeIf(k -> k.startsWith(product + "|"));
        }
        db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM purchase_counts WHERE product=?")) {
                ps.setString(1, product);
                ps.executeUpdate();
            }
        });
    }

    // ---------------- 懲罰與欠款 ----------------

    public void ban(UUID id, String name, long until, String reason) {
        PlayerData d = online.get(id);
        if (d != null) {
            d.bannedUntil = Math.max(d.bannedUntil, until);
            d.banReason = reason;
        }
        db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO penalties(player,name,until,reason) VALUES(?,?,?,?) ON CONFLICT(player) DO UPDATE SET name=excluded.name, until=MAX(until, excluded.until), reason=excluded.reason")) {
                ps.setString(1, id.toString());
                ps.setString(2, name);
                ps.setLong(3, until);
                ps.setString(4, reason);
                ps.executeUpdate();
            }
        });
    }

    public void addDebt(UUID id, String name, String currency, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return;
        }
        PlayerData d = online.get(id);
        if (d != null) {
            d.debts.merge(currency, amount, BigDecimal::add);
        }
        db.execute(c -> {
            BigDecimal cur = BigDecimal.ZERO;
            try (PreparedStatement ps = c.prepareStatement("SELECT amount FROM debts WHERE player=? AND currency=?")) {
                ps.setString(1, id.toString());
                ps.setString(2, currency);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        cur = parse(rs.getString(1));
                    }
                }
            }
            writeDebt(c, id, currency, cur.add(amount));
            ensurePenaltyRow(c, id, name);
        });
    }

    /** 設定欠款餘額（還款後）。 */
    public void setDebt(UUID id, String currency, BigDecimal remaining) {
        PlayerData d = online.get(id);
        if (d != null) {
            if (remaining.signum() <= 0) {
                d.debts.remove(currency);
            } else {
                d.debts.put(currency, remaining);
            }
        }
        db.execute(c -> writeDebt(c, id, currency, remaining));
    }

    private static void writeDebt(Connection c, UUID id, String currency, BigDecimal amount) throws SQLException {
        if (amount.signum() <= 0) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM debts WHERE player=? AND currency=?")) {
                ps.setString(1, id.toString());
                ps.setString(2, currency);
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO debts(player,currency,amount) VALUES(?,?,?) ON CONFLICT(player,currency) DO UPDATE SET amount=excluded.amount")) {
            ps.setString(1, id.toString());
            ps.setString(2, currency);
            ps.setString(3, amount.toPlainString());
            ps.executeUpdate();
        }
    }

    private static void ensurePenaltyRow(Connection c, UUID id, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO penalties(player,name,until,reason) VALUES(?,?,0,'') ON CONFLICT(player) DO UPDATE SET name=excluded.name")) {
            ps.setString(1, id.toString());
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    /** 管理員解除：清除禁令與欠款。 */
    public void lift(UUID id) {
        PlayerData d = online.get(id);
        if (d != null) {
            d.bannedUntil = 0;
            d.banReason = null;
            d.debts.clear();
        }
        db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM penalties WHERE player=?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM debts WHERE player=?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
        });
    }

    /** 受罰名單（仍在禁令中或有欠款）。 */
    public CompletableFuture<List<PenaltyRow>> penalties(long now) {
        return db.submit(c -> {
            Map<UUID, PenaltyRow> rows = new LinkedHashMap<>();
            Map<UUID, Map<String, BigDecimal>> debts = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT player, currency, amount FROM debts")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID u = UUID.fromString(rs.getString(1));
                        debts.computeIfAbsent(u, k -> new LinkedHashMap<>()).put(rs.getString(2), parse(rs.getString(3)));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT player, name, until, reason FROM penalties ORDER BY until DESC")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID u = UUID.fromString(rs.getString(1));
                        long until = rs.getLong(3);
                        Map<String, BigDecimal> dm = debts.getOrDefault(u, Map.of());
                        if (until > now || !dm.isEmpty()) {
                            rows.put(u, new PenaltyRow(u, rs.getString(2), until, rs.getString(4), dm));
                        }
                    }
                }
            }
            return new ArrayList<>(rows.values());
        });
    }
}
