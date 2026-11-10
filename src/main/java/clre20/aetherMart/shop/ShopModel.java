package clre20.aetherMart.shop;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 系統商店的資料結構。 */
public final class ShopModel {

    private ShopModel() {
    }

    public enum LimitPeriod { NONE, DAILY, WEEKLY, TOTAL }

    public static final class Category {
        public final String id;
        public String name;
        public Material icon;
        public final List<Product> products = new ArrayList<>();

        public Category(String id, String name, Material icon) {
            this.id = id;
            this.name = name;
            this.icon = icon;
        }
    }

    /** 促銷：percent > 0 表示打折 %，否則用 price。 */
    public record Promotion(String id, long start, long end, BigDecimal percent, BigDecimal price) {

        public boolean active(long now) {
            return now >= start && now < end;
        }

        public boolean overlaps(Promotion o) {
            return start < o.end && o.start < end;
        }
    }

    public static final class Product {
        public final String id;
        public String categoryId;
        public ItemStack item;
        public String currency;
        public BigDecimal price;
        public int unit;
        public LimitPeriod limitPeriod = LimitPeriod.NONE;
        public int limitAmount;
        public final List<Promotion> promotions = new ArrayList<>();
        /** 快取：名稱搜尋與顯示用（由 ShopService 設定）。 */
        public transient String displayName = "";

        public Product(String id, String categoryId, ItemStack item, String currency, BigDecimal price, int unit) {
            this.id = id;
            this.categoryId = categoryId;
            this.item = item;
            this.currency = currency;
            this.price = price;
            this.unit = unit;
        }

        public Promotion activePromotion(long now) {
            for (Promotion p : promotions) {
                if (p.active(now)) {
                    return p;
                }
            }
            return null;
        }

        public Product copy() {
            Product p = new Product(id, categoryId, item.clone(), currency, price, unit);
            p.limitPeriod = limitPeriod;
            p.limitAmount = limitAmount;
            p.promotions.addAll(promotions);
            p.displayName = displayName;
            return p;
        }
    }
}
