package clre20.aetherMart.shop;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.data.TradeRecord;
import clre20.aetherMart.player.ClaimEntry;
import clre20.aetherMart.player.PlayerData;
import clre20.aetherMart.util.Inventories;
import clre20.aetherMart.util.Vars;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 系統商店：分類、商品、促銷、購買上限與購買流程。只在主執行緒操作。 */
public final class ShopService {

    public record Quote(BigDecimal unitPrice, BigDecimal subtotal, BigDecimal tax, BigDecimal total, int items,
                        ShopModel.Promotion promotion) {
    }

    private final AetherMart plugin;
    private final ShopStore store;
    private final List<ShopModel.Category> categories = new ArrayList<>();
    private final Map<String, ShopModel.Product> products = new HashMap<>();
    private boolean dirty;
    private boolean broken;
    private BukkitTask saveTask;

    public ShopService(AetherMart plugin) {
        this.plugin = plugin;
        this.store = new ShopStore(plugin);
    }

    public void load() {
        categories.clear();
        products.clear();
        try {
            categories.addAll(store.load());
            broken = false;
        } catch (IllegalStateException e) {
            broken = true;
        }
        for (ShopModel.Category c : categories) {
            for (ShopModel.Product p : c.products) {
                products.put(p.id, p);
            }
        }
        refreshNames();
        if (saveTask == null) {
            saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, 200L, 200L);
        }
    }

    public void refreshNames() {
        for (ShopModel.Product p : products.values()) {
            p.displayName = plugin.names().name(p.item);
        }
    }

    public List<ShopModel.Category> categories() {
        return categories;
    }

    public ShopModel.Category category(String id) {
        for (ShopModel.Category c : categories) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    public ShopModel.Product product(String id) {
        return products.get(id);
    }

    // ---------------- 編輯 ----------------

    public void markDirty() {
        dirty = true;
    }

    public void saveIfDirty() {
        if (!dirty || broken) {
            return;
        }
        dirty = false;
        String content = store.serialize(categories, i -> i.getType().name() + " " + plugin.names().name(i));
        plugin.worker().execute(() -> store.write(content));
    }

    /** 關服時同步寫入。 */
    public void saveNow() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (dirty && !broken) {
            dirty = false;
            store.write(store.serialize(categories, i -> i.getType().name() + " " + plugin.names().name(i)));
        }
    }

    public ShopModel.Category addCategory(String name) {
        ShopModel.Category c = new ShopModel.Category(newId(), name, Material.CHEST);
        categories.add(c);
        markDirty();
        return c;
    }

    public void removeCategory(ShopModel.Category c) {
        categories.remove(c);
        for (ShopModel.Product p : c.products) {
            products.remove(p.id);
            plugin.players().removeProductCounts(p.id);
        }
        markDirty();
    }

    public void moveCategory(ShopModel.Category c, int delta) {
        int i = categories.indexOf(c);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= categories.size()) {
            return;
        }
        categories.remove(i);
        categories.add(j, c);
        markDirty();
    }

    /** 新增或更新商品（編輯頁按儲存時呼叫）。 */
    public void saveProduct(ShopModel.Product draft) {
        ShopModel.Category cat = category(draft.categoryId);
        if (cat == null) {
            return;
        }
        ShopModel.Product existing = products.get(draft.id);
        if (existing == null) {
            ShopModel.Product p = draft.copy();
            p.displayName = plugin.names().name(p.item);
            cat.products.add(p);
            products.put(p.id, p);
        } else {
            existing.item = draft.item.clone();
            existing.currency = draft.currency;
            existing.price = draft.price;
            existing.unit = draft.unit;
            existing.limitPeriod = draft.limitPeriod;
            existing.limitAmount = draft.limitAmount;
            existing.promotions.clear();
            existing.promotions.addAll(draft.promotions);
            existing.displayName = plugin.names().name(existing.item);
        }
        markDirty();
    }

    public void removeProduct(ShopModel.Product p) {
        ShopModel.Category cat = category(p.categoryId);
        if (cat != null) {
            cat.products.removeIf(x -> x.id.equals(p.id));
        }
        products.remove(p.id);
        plugin.players().removeProductCounts(p.id);
        markDirty();
    }

    public void moveProduct(ShopModel.Product p, int delta) {
        ShopModel.Category cat = category(p.categoryId);
        if (cat == null) {
            return;
        }
        int i = -1;
        for (int k = 0; k < cat.products.size(); k++) {
            if (cat.products.get(k).id.equals(p.id)) {
                i = k;
            }
        }
        int j = i + delta;
        if (i < 0 || j < 0 || j >= cat.products.size()) {
            return;
        }
        ShopModel.Product x = cat.products.remove(i);
        cat.products.add(j, x);
        markDirty();
    }

    public String newId() {
        String id;
        do {
            id = UUID.randomUUID().toString().substring(0, 8);
        } while (products.containsKey(id) || category(id) != null);
        return id;
    }

    // ---------------- 價格 ----------------

    public BigDecimal unitPrice(ShopModel.Product p, long now) {
        Currency c = plugin.currencies().get(p.currency);
        BigDecimal price = p.price;
        ShopModel.Promotion promo = p.activePromotion(now);
        if (promo != null) {
            if (promo.percent() != null && promo.percent().signum() > 0) {
                price = p.price.multiply(BigDecimal.valueOf(100).subtract(promo.percent())).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            } else if (promo.price() != null) {
                price = promo.price();
            }
        }
        BigDecimal min = plugin.market().shopMin(p.currency);
        if (price.compareTo(min) < 0) {
            price = min;
        }
        return c == null ? price : c.round(price);
    }

    public Quote quote(ShopModel.Product p, int portions, long now) {
        Currency c = plugin.currencies().get(p.currency);
        BigDecimal unit = unitPrice(p, now);
        BigDecimal subtotal = unit.multiply(BigDecimal.valueOf(portions));
        BigDecimal tax = c == null ? BigDecimal.ZERO : c.tax(subtotal, plugin.market().shopTax(p.currency));
        return new Quote(unit, subtotal, tax, subtotal.add(tax), portions * p.unit, p.activePromotion(now));
    }

    // ---------------- 購買上限 ----------------

    public String period(ShopModel.LimitPeriod lp, long now) {
        ZoneId zone = plugin.config().zone;
        int[] reset = plugin.market().shopDailyReset;
        LocalDateTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
                .minusHours(reset[0]).minusMinutes(reset[1]);
        return switch (lp) {
            case DAILY -> "D:" + t.toLocalDate();
            case WEEKLY -> {
                DayOfWeek d = plugin.market().shopWeeklyDay;
                yield "W:" + t.toLocalDate().with(TemporalAdjusters.previousOrSame(d));
            }
            case TOTAL -> "T";
            case NONE -> "";
        };
    }

    public Set<String> currentPeriods(long now) {
        return Set.of(period(ShopModel.LimitPeriod.DAILY, now), period(ShopModel.LimitPeriod.WEEKLY, now));
    }

    /** 本期還能買幾個（-1 = 不限）。 */
    public int remaining(PlayerData d, ShopModel.Product p, long now) {
        if (p.limitPeriod == ShopModel.LimitPeriod.NONE || p.limitAmount <= 0) {
            return -1;
        }
        int bought = plugin.players().purchased(d, p.id, period(p.limitPeriod, now));
        return Math.max(0, p.limitAmount - bought);
    }

    // ---------------- 購買 ----------------

    /** 送出按鈕用：目前不能購買的原因（不扣款、不送訊息）；空的代表可以買。 */
    public List<String> buyBlockers(Player player, ShopModel.Product p, int portions) {
        List<String> out = new ArrayList<>();
        var g = plugin.guard();
        if (!player.hasPermission("aethermart.use")) {
            out.add(g.reason("reason.no-permission", Map.of()));
            return out;
        }
        if (products.get(p.id) != p) {
            out.add(g.reason("reason.shop-gone", Map.of()));
            return out;
        }
        Currency c = plugin.currencies().get(p.currency);
        if (c == null || !c.available()) {
            out.add(g.reason("reason.currency-unavailable", Vars.of("currency", c == null ? p.currency : c.name())));
            return out;
        }
        out.addAll(g.reasons(player, true));
        long now = System.currentTimeMillis();
        Quote q = quote(p, portions, now);
        PlayerData d = plugin.players().get(player.getUniqueId());
        if (d != null) {
            int left = remaining(d, p, now);
            if (left >= 0 && q.items() > left) {
                out.add(g.reason("reason.shop-limit", Vars.of("left", Math.max(0, left))));
            }
        }
        ItemStack stack = p.item.clone();
        stack.setAmount(1);
        if (!Inventories.fits(player.getInventory(), stack, q.items())) {
            out.add(g.reason("reason.no-space", Map.of()));
        }
        BigDecimal balance = c.round(c.provider().balance(player));
        if (balance.compareTo(q.total()) < 0) {
            out.add(g.reason("reason.not-enough", Vars.of("amount", c.format(q.total()), "balance", c.format(balance))));
        }
        return out;
    }

    /** 購買；成功或失敗都會送出訊息。回傳是否成功。 */
    public boolean buy(Player player, ShopModel.Product p, int portions) {
        return buy(player, p, portions, null);
    }

    /** 購買；成功時把結果畫面用的明細放進 out（item、amount、paid、tax、balance、overflow）。 */
    public boolean buy(Player player, ShopModel.Product p, int portions, Map<String, String> out) {
        if (portions <= 0 || portions > 2304) {
            return false;
        }
        if (!player.hasPermission("aethermart.use")) {
            plugin.messages().send(player, "common.no-permission");
            return false;
        }
        if (products.get(p.id) != p) {
            plugin.messages().send(player, "shop.gone");
            return false;
        }
        Currency c = plugin.currencies().get(p.currency);
        if (c == null || !c.available()) {
            plugin.messages().send(player, "currency.unavailable", Vars.of("currency", c == null ? p.currency : c.name()));
            return false;
        }
        if (!plugin.guard().check(player, true)) {
            return false;
        }
        long now = System.currentTimeMillis();
        PlayerData d = plugin.players().get(player.getUniqueId());
        Quote q = quote(p, portions, now);
        int left = remaining(d, p, now);
        if (left >= 0 && q.items() > left) {
            plugin.messages().send(player, "shop.limit", Vars.of("left", left, "limit", p.limitAmount));
            return false;
        }
        ItemStack stack = p.item.clone();
        stack.setAmount(1);
        if (!Inventories.fits(player.getInventory(), stack, q.items())) {
            plugin.messages().send(player, "shop.no-space");
            return false;
        }
        if (!c.provider().withdraw(player, q.total())) {
            plugin.messages().send(player, "common.not-enough", Vars.of("amount", c.format(q.total())));
            return false;
        }
        List<ItemStack> overflow = Inventories.give(player.getInventory(), stack, q.items());
        if (!overflow.isEmpty()) {
            plugin.claims().add(ClaimEntry.items(player.getUniqueId(), overflow, ClaimEntry.Source.BOUGHT));
        }
        if (p.limitPeriod != ShopModel.LimitPeriod.NONE && p.limitAmount > 0) {
            plugin.players().addPurchase(player.getUniqueId(), p.id, period(p.limitPeriod, now), q.items());
        }
        String summary = p.displayName + " x" + q.items();
        String receiptNo = plugin.meta().nextReceipt(plugin.config().zone);
        plugin.trades().insert(new TradeRecord(0, now, TradeRecord.Type.SHOP_BUY, player.getUniqueId(), player.getName(),
                null, null, c.id(), q.subtotal().toPlainString(), q.tax().toPlainString(), summary, 0, receiptNo));
        plugin.activity().log("TRADE", "SHOP_BUY " + player.getName() + " (" + player.getUniqueId() + ") " + summary
                + " product=" + p.id + " paid=" + c.format(q.total()) + " tax=" + c.format(q.tax()) + " receipt=" + receiptNo);
        plugin.messages().send(player, "shop.bought", Vars.of("item", p.displayName, "amount", q.items(), "price", c.format(q.total())));
        plugin.sounds().success(player);
        BigDecimal balance = c.provider().balance(player);
        plugin.receipts().shopPurchase(player, p, q, c, balance, receiptNo, now);
        if (out != null) {
            out.put("item", p.displayName);
            out.put("amount", String.valueOf(q.items()));
            out.put("paid", c.format(q.total()));
            out.put("tax", c.format(q.tax()));
            out.put("balance", c.format(c.round(balance)));
            out.put("overflow", String.valueOf(!overflow.isEmpty()));
        }
        return true;
    }
}
