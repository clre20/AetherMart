package clre20.aetherMart.discord;

import clre20.aetherMart.AetherMart;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 讀 DiscordLink 的綁定資料（唯讀開它的 SQLite，不修改 DiscordLink）。
 * 線上玩家進服時查一次、之後定時批次更新；查詢都在自己的背景執行緒。
 */
public final class LinkReader {

    private static final Pattern DB_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final AetherMart plugin;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AetherMart-Link");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, String> linked = new ConcurrentHashMap<>();
    private final Set<UUID> bypass = ConcurrentHashMap.newKeySet();
    private final Set<UUID> known = ConcurrentHashMap.newKeySet();
    private volatile boolean ok;
    private volatile boolean warned;
    private Connection conn;
    private File dbFile;

    public LinkReader(AetherMart plugin) {
        this.plugin = plugin;
    }

    public boolean available() {
        Plugin p = Bukkit.getPluginManager().getPlugin("DiscordLink");
        return p != null && p.isEnabled() && ok;
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
        return linked.containsKey(id) || (plugin.config().allowBypass && bypass.contains(id));
    }

    public boolean isLinked(UUID id) {
        return linked.containsKey(id);
    }

    public boolean known(UUID id) {
        return known.contains(id);
    }

    public void start() {
        long period = plugin.config().linkRefreshSeconds * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOnline, 40L, period);
    }

    public void onJoin(UUID id) {
        thread.execute(() -> query(List.of(id)));
    }

    public void onQuit(UUID id) {
        linked.remove(id);
        bypass.remove(id);
        known.remove(id);
    }

    private void refreshOnline() {
        List<UUID> ids = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ids.add(p.getUniqueId());
        }
        thread.execute(() -> query(ids));
    }

    /** 背景：更新一批玩家的綁定狀態。 */
    private void query(List<UUID> ids) {
        if (!openIfNeeded()) {
            return;
        }
        try {
            for (UUID id : ids) {
                String dc = discordIdUnsafe(id);
                if (dc != null) {
                    linked.put(id, dc);
                } else {
                    linked.remove(id);
                }
                if (isBypassUnsafe(id)) {
                    bypass.add(id);
                } else {
                    bypass.remove(id);
                }
                known.add(id);
            }
            ok = true;
        } catch (SQLException e) {
            fail(e);
        }
    }

    /** 任何執行緒：查詢玩家綁定的 Discord ID（會在 Link 執行緒等待結果）。 */
    public Optional<String> discordId(UUID id) {
        String cached = linked.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            return thread.submit(() -> {
                if (!openIfNeeded()) {
                    return Optional.<String>empty();
                }
                try {
                    return Optional.ofNullable(discordIdUnsafe(id));
                } catch (SQLException e) {
                    fail(e);
                    return Optional.<String>empty();
                }
            }).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String discordIdUnsafe(UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT dc_id FROM bindings WHERE uuid=?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String dc = rs.getString(1);
                    return dc != null && dc.matches("\\d{5,25}") ? dc : null;
                }
            }
        }
        return null;
    }

    private boolean isBypassUnsafe(UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM bypass_list WHERE uuid=?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean openIfNeeded() {
        try {
            Plugin dl = Bukkit.getPluginManager().getPlugin("DiscordLink");
            if (dl == null || !dl.isEnabled()) {
                closeConn();
                ok = false;
                return false;
            }
            File folder = dl.getDataFolder();
            File file = resolveDb(folder);
            if (conn != null && !conn.isClosed() && file.equals(dbFile)) {
                return true;
            }
            closeConn();
            if (!file.isFile()) {
                throw new SQLException("DiscordLink database not found");
            }
            Properties props = new Properties();
            props.setProperty("open_mode", "1");
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath(), props);
            conn.setReadOnly(true);
            try (var st = conn.createStatement()) {
                st.execute("PRAGMA busy_timeout=3000");
            }
            dbFile = file;
            return true;
        } catch (SQLException e) {
            fail(e);
            return false;
        }
    }

    private static File resolveDb(File folder) {
        String name = "database.db";
        File cfg = new File(folder, "config.yml");
        if (cfg.isFile()) {
            String n = YamlConfiguration.loadConfiguration(cfg).getString("database.sqlite-file-name", "database.db");
            if (n != null && DB_NAME.matcher(n).matches() && !n.contains("..")) {
                name = n;
            }
        }
        return new File(folder, name);
    }

    private void fail(Exception e) {
        ok = false;
        closeConn();
        if (!warned) {
            warned = true;
            plugin.getLogger().warning("Cannot read DiscordLink bindings (link checks will deny, personal notices paused): " + e.getMessage());
        }
    }

    private void closeConn() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
            conn = null;
        }
    }

    public void shutdown() {
        thread.execute(this::closeConn);
        thread.shutdown();
        try {
            thread.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
