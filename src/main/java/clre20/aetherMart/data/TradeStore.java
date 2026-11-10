package clre20.aetherMart.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 交易紀錄的寫入與查詢（都在資料庫執行緒）。 */
public final class TradeStore {

    private final Database db;

    public TradeStore(Database db) {
        this.db = db;
    }

    public void insert(TradeRecord r) {
        db.execute(c -> insert(c, r));
    }

    public static void insert(Connection c, TradeRecord r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO trades(time,type,actor,actor_name,other,other_name,currency,amount,tax,summary,auction_id,receipt) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, r.time());
            ps.setString(2, r.type().name());
            ps.setString(3, r.actor() == null ? null : r.actor().toString());
            ps.setString(4, r.actorName());
            ps.setString(5, r.other() == null ? null : r.other().toString());
            ps.setString(6, r.otherName());
            ps.setString(7, r.currency());
            ps.setString(8, r.amount());
            ps.setString(9, r.tax());
            ps.setString(10, r.summary());
            ps.setLong(11, r.auctionId());
            ps.setString(12, r.receipt());
            ps.executeUpdate();
        }
    }

    /** 查詢（新到舊）；player 為 null 表示全部，type 為 null 表示全部類型。 */
    public CompletableFuture<List<TradeRecord>> query(UUID player, TradeRecord.Type type, int offset, int limit) {
        return db.submit(c -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM trades WHERE 1=1");
            if (player != null) {
                sql.append(" AND (actor=? OR other=?)");
            }
            if (type != null) {
                sql.append(" AND type=?");
            }
            sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                int i = 1;
                if (player != null) {
                    ps.setString(i++, player.toString());
                    ps.setString(i++, player.toString());
                }
                if (type != null) {
                    ps.setString(i++, type.name());
                }
                ps.setInt(i++, limit);
                ps.setInt(i, offset);
                return read(ps);
            }
        });
    }

    /** 期間內的交易（每日彙整），舊到新。 */
    public CompletableFuture<List<TradeRecord>> between(long from, long to) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM trades WHERE time>=? AND time<? ORDER BY time ASC, id ASC")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                return read(ps);
            }
        });
    }

    public void deleteOlderThan(long time) {
        db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM trades WHERE time<?")) {
                ps.setLong(1, time);
                ps.executeUpdate();
            }
        });
    }

    private static List<TradeRecord> read(PreparedStatement ps) throws SQLException {
        List<TradeRecord> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TradeRecord.Type t = TradeRecord.Type.parse(rs.getString("type"));
                if (t == null) {
                    continue;
                }
                out.add(new TradeRecord(rs.getLong("id"), rs.getLong("time"), t, uuid(rs.getString("actor")),
                        rs.getString("actor_name"), uuid(rs.getString("other")), rs.getString("other_name"),
                        rs.getString("currency"), rs.getString("amount"), rs.getString("tax"), rs.getString("summary"),
                        rs.getLong("auction_id"), rs.getString("receipt")));
            }
        }
        return out;
    }

    static UUID uuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
