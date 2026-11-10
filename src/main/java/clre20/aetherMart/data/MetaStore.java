package clre20.aetherMart.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 小型鍵值資料：上次結算時間、明細編號流水號、休市狀態等。啟動時全部載入記憶體。 */
public final class MetaStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Database db;
    private final Map<String, String> values = new ConcurrentHashMap<>();

    public MetaStore(Database db) {
        this.db = db;
    }

    public void load() throws Exception {
        db.call(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT key, value FROM meta")) {
                while (rs.next()) {
                    values.put(rs.getString(1), rs.getString(2));
                }
            }
            return null;
        }, 30);
    }

    public String get(String key, String def) {
        return values.getOrDefault(key, def);
    }

    public long getLong(String key, long def) {
        try {
            return Long.parseLong(values.getOrDefault(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void set(String key, String value) {
        values.put(key, value);
        db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        });
    }

    /** 下一個明細編號，例如 AM-20261026-000123（每天從 1 起算）。 */
    public synchronized String nextReceipt(ZoneId zone) {
        String day = LocalDate.now(zone).format(DAY);
        String last = get("receipt.day", "");
        long n = day.equals(last) ? getLong("receipt.seq", 0) + 1 : 1;
        if (!day.equals(last)) {
            set("receipt.day", day);
        }
        set("receipt.seq", String.valueOf(n));
        return "AM-" + day + "-" + String.format("%06d", n);
    }

    /** 下一個拍賣編號。 */
    public synchronized long nextAuctionId(long minimum) {
        long n = Math.max(getLong("auction.seq", 0), minimum) + 1;
        set("auction.seq", String.valueOf(n));
        return n;
    }
}
