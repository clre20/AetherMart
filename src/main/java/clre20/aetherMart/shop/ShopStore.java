package clre20.aetherMart.shop;

import clre20.aetherMart.config.YamlFiles;
import clre20.aetherMart.util.ItemCodec;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** data/shop-items.yml：分類、商品與促銷排程。 */
public final class ShopStore {

    private final JavaPlugin plugin;
    private final File file;

    public ShopStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/shop-items.yml");
    }

    public List<ShopModel.Category> load() {
        List<ShopModel.Category> out = new ArrayList<>();
        if (!file.exists()) {
            return out;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            try {
                Files.copy(file.toPath(), file.toPath().resolveSibling(file.getName() + ".broken-" + stamp), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            plugin.getLogger().severe("data/shop-items.yml is broken (backup saved); shop is empty until fixed: " + e.getMessage());
            throw new IllegalStateException("broken shop file");
        }
        for (Map<?, ?> cm : y.getMapList("categories")) {
            try {
                String id = str(cm.get("id"));
                Material icon = Material.matchMaterial(str(cm.get("icon")));
                ShopModel.Category cat = new ShopModel.Category(id, str(cm.get("name")), icon == null || !icon.isItem() ? Material.CHEST : icon);
                Object plist = cm.get("products");
                if (plist instanceof List<?> list) {
                    for (Object po : list) {
                        if (!(po instanceof Map<?, ?> pm)) {
                            continue;
                        }
                        try {
                            cat.products.add(readProduct(pm, id));
                        } catch (RuntimeException e) {
                            plugin.getLogger().warning("Skipped a broken shop product in category " + id + ": " + e.getMessage());
                        }
                    }
                }
                out.add(cat);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Skipped a broken shop category: " + e.getMessage());
            }
        }
        return out;
    }

    private static ShopModel.Product readProduct(Map<?, ?> pm, String categoryId) {
        ItemStack item = ItemCodec.decodeBase64(str(pm.get("item")));
        item.setAmount(1);
        ShopModel.Product p = new ShopModel.Product(str(pm.get("id")), categoryId, item, str(pm.get("currency")),
                new BigDecimal(str(pm.get("price"))), Math.max(1, Math.min(2304, Integer.parseInt(str(pm.get("unit"))))));
        if (pm.get("limit") instanceof Map<?, ?> lm) {
            try {
                p.limitPeriod = ShopModel.LimitPeriod.valueOf(str(lm.get("period")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                p.limitPeriod = ShopModel.LimitPeriod.NONE;
            }
            p.limitAmount = Math.max(0, Integer.parseInt(str(lm.get("amount"))));
        }
        if (pm.get("promotions") instanceof List<?> promos) {
            for (Object o : promos) {
                if (o instanceof Map<?, ?> m) {
                    String percent = str(m.get("percent"));
                    String price = str(m.get("price"));
                    p.promotions.add(new ShopModel.Promotion(str(m.get("id")), Long.parseLong(str(m.get("start"))),
                            Long.parseLong(str(m.get("end"))), percent.isEmpty() ? BigDecimal.ZERO : new BigDecimal(percent),
                            price.isEmpty() ? null : new BigDecimal(price)));
                }
            }
        }
        return p;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 在主執行緒把目前內容轉成文字（之後交給 I/O 執行緒寫檔）。 */
    public String serialize(List<ShopModel.Category> cats, java.util.function.Function<ItemStack, String> preview) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("AetherMart shop data. Edit in game with /mart admin shop."));
        List<Map<String, Object>> list = new ArrayList<>();
        for (ShopModel.Category c : cats) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.id);
            cm.put("name", c.name);
            cm.put("icon", c.icon.name());
            List<Map<String, Object>> plist = new ArrayList<>();
            for (ShopModel.Product p : c.products) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("id", p.id);
                pm.put("preview", preview.apply(p.item));
                pm.put("item", ItemCodec.encodeBase64(p.item));
                pm.put("currency", p.currency);
                pm.put("price", p.price.toPlainString());
                pm.put("unit", p.unit);
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("period", p.limitPeriod.name());
                lm.put("amount", p.limitAmount);
                pm.put("limit", lm);
                List<Map<String, Object>> promos = new ArrayList<>();
                for (ShopModel.Promotion pr : p.promotions) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", pr.id());
                    m.put("start", pr.start());
                    m.put("end", pr.end());
                    m.put("percent", pr.percent() == null ? "" : pr.percent().toPlainString());
                    m.put("price", pr.price() == null ? "" : pr.price().toPlainString());
                    promos.add(m);
                }
                pm.put("promotions", promos);
                plist.add(pm);
            }
            cm.put("products", plist);
            list.add(cm);
        }
        y.set("categories", list);
        return y.saveToString();
    }

    public void write(String content) {
        try {
            Files.createDirectories(file.getParentFile().toPath());
        } catch (IOException ignored) {
        }
        YamlFiles.write(plugin, file, content);
    }
}
