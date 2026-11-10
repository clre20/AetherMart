package clre20.aetherMart.auction;

import clre20.aetherMart.data.Database;
import clre20.aetherMart.util.ItemCodec;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** 禁止販售清單：完整 NBT 相同（不看數量）就算；潛影盒與束口袋會檢查裡面的物品。 */
public final class BannedItems {

    public static final class Entry {
        private volatile long id;
        private volatile boolean removed;
        private final ItemStack item;

        Entry(long id, ItemStack item) {
            this.id = id;
            this.item = item;
        }

        public long id() {
            return id;
        }

        public ItemStack item() {
            return item;
        }
    }

    private final Database db;
    private final Logger logger;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Material, List<ItemStack>> index = new EnumMap<>(Material.class);

    public BannedItems(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    public void load() throws Exception {
        List<Entry> loaded = db.call(c -> {
            List<Entry> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, item FROM banned_items ORDER BY id")) {
                while (rs.next()) {
                    try {
                        ItemStack i = ItemCodec.decode(rs.getBytes(2));
                        i.setAmount(1);
                        out.add(new Entry(rs.getLong(1), i));
                    } catch (RuntimeException e) {
                        logger.warning("Skipped unreadable banned item #" + rs.getLong(1));
                    }
                }
            }
            return out;
        }, 30);
        entries.clear();
        entries.addAll(loaded);
        reindex();
    }

    private void reindex() {
        index.clear();
        for (Entry e : entries) {
            index.computeIfAbsent(e.item().getType(), k -> new ArrayList<>()).add(e.item());
        }
    }

    public List<Entry> entries() {
        return entries;
    }

    /** 已有相同物品回傳 false。 */
    public boolean add(ItemStack item) {
        ItemStack one = item.clone();
        one.setAmount(1);
        if (matches(one)) {
            return false;
        }
        Entry placeholder = new Entry(0, one);
        entries.add(placeholder);
        reindex();
        byte[] blob = ItemCodec.encode(one);
        db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO banned_items(material,item) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, one.getType().name());
                ps.setBytes(2, blob);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }).thenAccept(id -> {
            placeholder.id = id;
            if (placeholder.removed) {
                deleteRow(id);
            }
        });
        return true;
    }

    public void remove(Entry e) {
        entries.remove(e);
        reindex();
        e.removed = true;
        if (e.id > 0) {
            deleteRow(e.id);
        }
    }

    private void deleteRow(long id) {
        db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM banned_items WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        });
    }

    private boolean matches(ItemStack item) {
        List<ItemStack> list = index.get(item.getType());
        if (list == null) {
            return false;
        }
        for (ItemStack b : list) {
            if (b.isSimilar(item)) {
                return true;
            }
        }
        return false;
    }

    /** 物品本身或裡面的物品是否被禁止。 */
    public boolean isBanned(ItemStack item) {
        return isBanned(item, 0);
    }

    private boolean isBanned(ItemStack item, int depth) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (matches(item)) {
            return true;
        }
        if (depth > 3 || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof ShulkerBox box) {
            for (ItemStack inner : box.getInventory().getContents()) {
                if (isBanned(inner, depth + 1)) {
                    return true;
                }
            }
        }
        if (meta instanceof BundleMeta bm) {
            for (ItemStack inner : bm.getItems()) {
                if (isBanned(inner, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }
}
