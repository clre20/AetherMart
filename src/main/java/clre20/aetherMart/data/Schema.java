package clre20.aetherMart.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** 資料表。 */
final class Schema {

    private Schema() {
    }

    static void create(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS auctions (
                      id INTEGER PRIMARY KEY,
                      seller TEXT NOT NULL, seller_name TEXT NOT NULL,
                      mode TEXT NOT NULL, currency TEXT NOT NULL, price TEXT NOT NULL,
                      items BLOB NOT NULL, created INTEGER NOT NULL, ends INTEGER NOT NULL,
                      phase TEXT NOT NULL, knock INTEGER NOT NULL DEFAULT 0, next_knock INTEGER NOT NULL DEFAULT 0,
                      receipt TEXT NOT NULL DEFAULT '')""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS bids (
                      auction_id INTEGER NOT NULL, bidder TEXT NOT NULL, bidder_name TEXT NOT NULL,
                      amount TEXT NOT NULL, time INTEGER NOT NULL)""");
            st.execute("CREATE INDEX IF NOT EXISTS bids_auction ON bids(auction_id)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS claims (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, kind TEXT NOT NULL,
                      items BLOB, currency TEXT, amount TEXT, source TEXT NOT NULL, created INTEGER NOT NULL)""");
            st.execute("CREATE INDEX IF NOT EXISTS claims_owner ON claims(owner)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS trades (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, type TEXT NOT NULL,
                      actor TEXT, actor_name TEXT, other TEXT, other_name TEXT,
                      currency TEXT, amount TEXT, tax TEXT, summary TEXT, auction_id INTEGER, receipt TEXT)""");
            st.execute("CREATE INDEX IF NOT EXISTS trades_time ON trades(time)");
            st.execute("CREATE INDEX IF NOT EXISTS trades_actor ON trades(actor)");
            st.execute("CREATE INDEX IF NOT EXISTS trades_other ON trades(other)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS purchase_counts (
                      player TEXT NOT NULL, product TEXT NOT NULL, period TEXT NOT NULL, count INTEGER NOT NULL,
                      PRIMARY KEY (player, product, period))""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS penalties (
                      player TEXT PRIMARY KEY, name TEXT NOT NULL, until INTEGER NOT NULL, reason TEXT)""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS debts (
                      player TEXT NOT NULL, currency TEXT NOT NULL, amount TEXT NOT NULL,
                      PRIMARY KEY (player, currency))""");
            st.execute("CREATE TABLE IF NOT EXISTS settings (player TEXT PRIMARY KEY, flags INTEGER NOT NULL)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS notify_queue (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, target TEXT NOT NULL,
                      player TEXT, kind TEXT NOT NULL, payload TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS daily_receipts (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, player TEXT NOT NULL,
                      player_name TEXT NOT NULL, flag INTEGER NOT NULL, payload TEXT NOT NULL)""");
            st.execute("CREATE INDEX IF NOT EXISTS daily_receipts_player ON daily_receipts(player, time)");
            st.execute("CREATE INDEX IF NOT EXISTS daily_receipts_time ON daily_receipts(time)");
            st.execute("CREATE TABLE IF NOT EXISTS banned_items (id INTEGER PRIMARY KEY AUTOINCREMENT, material TEXT NOT NULL, item BLOB NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
    }
}
