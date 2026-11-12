package clre20.aetherMart.discord;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.pdf.Receipt;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 每日收據：購買、拍賣購買、得標、賣出的收據先存進 SQLite（daily_receipts），
 * 到結算時間（discord.admin.daily-summary.time）替每位有收據的玩家排一則待寄項目。
 * 結算當下只做一次查詢；PDF 由 Notifier 的 Discord 執行緒一位一位產生並寄出。
 */
public final class DailyReceipts {

    private static final String LAST = "receipts.last";
    /** 收據最多保留幾天（Bot 長期離線或寄送失敗時，避免資料無限累積）。 */
    static final long KEEP_MS = 7 * 86_400_000L;
    /** 一位玩家一次結算最多讀幾筆（防止小號互相交易灌爆，結算時一次讀進大量資料）。 */
    static final int MAX_PER_SETTLEMENT = 1000;
    /** 這幾種改成每日寄送。 */
    private static final Set<String> KEYS = Set.of("discord.dm.shop-purchase", "discord.dm.auction-buy", "discord.dm.won", "discord.dm.sold");

    public record Row(int flag, Receipt receipt) {
    }

    private final AetherMart plugin;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private boolean running;
    private long lastMaintenance;

    public DailyReceipts(AetherMart plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::check, 100L, 200L);
    }

    /** 這個通知是否改成每日寄送。 */
    public boolean handles(String key) {
        return plugin.config().dailyReceiptsEnabled && KEYS.contains(key);
    }

    /** 交易當下呼叫：只排一筆寫入到資料庫執行緒。 */
    public void store(UUID player, String name, int flag, Receipt receipt) {
        long time = System.currentTimeMillis();
        String json = gson.toJson(receipt);
        String id = player.toString();
        String n = name == null ? id : name;
        plugin.db().execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO daily_receipts(time,player,player_name,flag,payload) VALUES(?,?,?,?,?)")) {
                ps.setLong(1, time);
                ps.setString(2, id);
                ps.setString(3, n);
                ps.setInt(4, flag);
                ps.setString(5, json);
                ps.executeUpdate();
            }
        });
        plugin.activity().log("DISCORD", "stored daily receipt " + receipt.number + " for " + n);
    }

    private long boundaryAtOrBefore(long now) {
        ZoneId zone = plugin.config().zone;
        int[] t = plugin.config().dailyTime;
        ZonedDateTime n = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone);
        ZonedDateTime b = n.toLocalDate().atTime(LocalTime.of(t[0], t[1])).atZone(zone);
        if (b.isAfter(n)) {
            b = b.minusDays(1);
        }
        return b.toInstant().toEpochMilli();
    }

    /** 主執行緒每 10 秒：只算一次時間，過了結算時間才丟一個查詢到資料庫執行緒。 */
    private void check() {
        long now = System.currentTimeMillis();
        if (now - lastMaintenance > 3_600_000L) {
            lastMaintenance = now;
            long cutoff = now - KEEP_MS;
            plugin.db().execute(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM daily_receipts WHERE time<?")) {
                    ps.setLong(1, cutoff);
                    ps.executeUpdate();
                }
            });
        }
        if (running) {
            return;
        }
        long boundary = boundaryAtOrBefore(now);
        long last = plugin.meta().getLong(LAST, 0);
        if (last == 0) {
            plugin.meta().set(LAST, String.valueOf(boundary));
            return;
        }
        if (boundary <= last) {
            return;
        }
        running = true;
        long from = last;
        plugin.db().submit(c -> {
            Map<String, String> people = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player, player_name FROM daily_receipts WHERE id IN "
                            + "(SELECT MAX(id) FROM daily_receipts WHERE time>=? AND time<? GROUP BY player) ORDER BY id")) {
                ps.setLong(1, from);
                ps.setLong(2, boundary);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        people.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return people;
        }).whenComplete((people, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (err != null) {
                    plugin.getLogger().warning("Daily receipts query failed: " + err);
                    return;
                }
                for (Map.Entry<String, String> e : people.entrySet()) {
                    plugin.notifier().receipts(e.getKey(), e.getValue(), from, boundary);
                }
                plugin.meta().set(LAST, String.valueOf(boundary));
                plugin.activity().log("DISCORD", "daily receipts settled: " + people.size() + " player(s)");
            } finally {
                running = false;
            }
        }));
    }

    /** 讀出一位玩家在期間內的收據（呼叫端在 Discord 執行緒等待）。 */
    public CompletableFuture<List<Row>> load(String player, long from, long to) {
        return plugin.db().submit(c -> {
            List<Row> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, flag, payload FROM daily_receipts WHERE player=? AND time>=? AND time<? ORDER BY time, id LIMIT "
                            + MAX_PER_SETTLEMENT)) {
                ps.setString(1, player);
                ps.setLong(2, from);
                ps.setLong(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            Receipt r = gson.fromJson(rs.getString(3), Receipt.class);
                            if (r != null) {
                                out.add(new Row(rs.getInt(2), r));
                            }
                        } catch (RuntimeException e) {
                            plugin.getLogger().warning("Dropped unreadable daily receipt #" + rs.getLong(1));
                        }
                    }
                }
            }
            return out;
        });
    }

    /** 寄出（或放棄）後刪除這段期間的收據。 */
    public void delete(String player, long from, long to) {
        plugin.db().execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM daily_receipts WHERE player=? AND time>=? AND time<?")) {
                ps.setString(1, player);
                ps.setLong(2, from);
                ps.setLong(3, to);
                ps.executeUpdate();
            }
        });
    }
}
