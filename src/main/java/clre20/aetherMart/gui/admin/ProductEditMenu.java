package clre20.aetherMart.gui.admin;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.currency.CurrencyRegistry;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.shop.ShopModel;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 商品設定：價格、幣別、每次購買數量、購買上限、促銷排程、排序、刪除；按儲存才生效，Esc＝取消。 */
public final class ProductEditMenu extends Menu {

    private final ShopModel.Product draft;
    private final boolean isNew;
    private long deleteUntil;

    public ProductEditMenu(AetherMart plugin, Player viewer, ShopModel.Product draft, boolean isNew) {
        super(plugin, viewer);
        this.draft = draft;
        this.isNew = isNew;
    }

    @Override
    public String getMenuId() {
        return "admin_shop_product";
    }

    @Override
    protected String titleKey() {
        return isNew ? "gui.admin.product.title-new" : "gui.admin.product.title";
    }

    @Override
    protected int rows() {
        return 3;
    }

    ShopModel.Product draft() {
        return draft;
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, plugin.icons().filler());
        }
        Currency c = plugin.currencies().get(draft.currency);
        Map<String, String> vars = Vars.of("price", c == null ? draft.price.toPlainString() : c.format(draft.price),
                "currency", c == null ? draft.currency : c.name(), "unit", draft.unit,
                "period", plain("shop.period." + draft.limitPeriod.name().toLowerCase(), Map.of()),
                "limit", draft.limitAmount, "promos", draft.promotions.size(),
                "min", c == null ? "" : c.format(plugin.market().shopMin(draft.currency)));
        set(4, plugin.icons().withLore(draft.item, plugin.messages().list("gui.admin.product.preview"), vars));
        set(10, plugin.icons().of(Material.GOLD_INGOT, "gui.admin.product.price", vars), e -> {
            Currency cur = plugin.currencies().get(draft.currency);
            if (cur == null) {
                return;
            }
            ask("admin.input.price", Vars.of("min", cur.format(plugin.market().shopMin(cur.id()))), text -> {
                BigDecimal v = CurrencyRegistry.parseAmount(text, cur, plugin.config().maxAmount);
                if (v == null) {
                    plugin.messages().send(viewer, "common.bad-amount", Vars.of("decimals", cur.decimals()));
                } else if (v.compareTo(plugin.market().shopMin(cur.id())) < 0) {
                    plugin.messages().send(viewer, "admin.shop.below-min", Vars.of("min", cur.format(plugin.market().shopMin(cur.id()))));
                } else {
                    draft.price = v;
                }
                reopen();
            });
        });
        set(11, plugin.icons().of(Material.GOLD_NUGGET, "gui.admin.product.currency", vars), e -> {
            List<String> ids = plugin.currencies().ids();
            int i = ids.indexOf(draft.currency);
            draft.currency = ids.get((i + 1) % ids.size());
            Currency cur = plugin.currencies().get(draft.currency);
            BigDecimal min = plugin.market().shopMin(draft.currency);
            if (cur != null) {
                draft.price = cur.round(draft.price.max(min));
            }
            refresh();
        });
        set(12, plugin.icons().of(Material.CHEST, "gui.admin.product.unit", vars), e -> ask("admin.input.unit", Map.of(), text -> {
            try {
                int n = Integer.parseInt(text.trim());
                if (n < 1 || n > 2304) {
                    throw new NumberFormatException();
                }
                draft.unit = n;
            } catch (NumberFormatException ex) {
                plugin.messages().send(viewer, "admin.shop.bad-number", Vars.of("min", 1, "max", 2304));
            }
            reopen();
        }));
        set(13, plugin.icons().of(Material.HOPPER, "gui.admin.product.period", vars), e -> {
            ShopModel.LimitPeriod[] v = ShopModel.LimitPeriod.values();
            draft.limitPeriod = v[(draft.limitPeriod.ordinal() + 1) % v.length];
            refresh();
        });
        set(14, plugin.icons().of(Material.COMPARATOR, "gui.admin.product.limit", vars), e -> ask("admin.input.limit", Map.of(), text -> {
            try {
                int n = Integer.parseInt(text.trim());
                if (n < 0 || n > 1_000_000) {
                    throw new NumberFormatException();
                }
                draft.limitAmount = n;
            } catch (NumberFormatException ex) {
                plugin.messages().send(viewer, "admin.shop.bad-number", Vars.of("min", 0, "max", 1000000));
            }
            reopen();
        }));
        set(15, plugin.icons().of(Material.CLOCK, "gui.admin.product.promotions", vars), e -> new PromotionMenu(plugin, viewer, this).open());
        if (!isNew) {
            set(19, plugin.icons().of(Material.SPECTRAL_ARROW, "gui.admin.common.move-up"), e -> {
                if (AdminCategoriesMenu.allowed(plugin, viewer)) {
                    plugin.shop().moveProduct(draft, -1);
                    plugin.messages().send(viewer, "admin.shop.moved");
                }
            });
            set(20, plugin.icons().of(Material.ARROW, "gui.admin.common.move-down"), e -> {
                if (AdminCategoriesMenu.allowed(plugin, viewer)) {
                    plugin.shop().moveProduct(draft, 1);
                    plugin.messages().send(viewer, "admin.shop.moved");
                }
            });
            boolean confirming = System.currentTimeMillis() < deleteUntil;
            set(22, plugin.icons().of(Material.LAVA_BUCKET, confirming ? "gui.admin.product.delete-confirm" : "gui.admin.product.delete"), e -> {
                if (!AdminCategoriesMenu.allowed(plugin, viewer)) {
                    return;
                }
                if (System.currentTimeMillis() >= deleteUntil) {
                    deleteUntil = System.currentTimeMillis() + 5000;
                    refresh();
                    return;
                }
                ShopModel.Product real = plugin.shop().product(draft.id);
                if (real != null) {
                    plugin.shop().removeProduct(real);
                    plugin.activity().log("ADMIN", viewer.getName() + " removed shop product " + draft.id + " " + draft.displayName);
                    plugin.messages().send(viewer, "admin.shop.product-deleted", Vars.of("item", draft.displayName));
                }
                new AdminProductsMenu(plugin, viewer, draft.categoryId, 0).open();
            });
        }
        set(18, plugin.icons().of(Material.BARRIER, "gui.admin.common.cancel"), e -> new AdminProductsMenu(plugin, viewer, draft.categoryId, 0).open());
        set(26, plugin.icons().of(Material.LIME_CONCRETE, "gui.admin.common.save"), e -> {
            if (!AdminCategoriesMenu.allowed(plugin, viewer)) {
                return;
            }
            if (plugin.shop().category(draft.categoryId) == null) {
                plugin.messages().send(viewer, "shop.gone");
                new AdminCategoriesMenu(plugin, viewer, 0).open();
                return;
            }
            if (!isNew && plugin.shop().product(draft.id) == null) {
                plugin.messages().send(viewer, "shop.gone");
                new AdminProductsMenu(plugin, viewer, draft.categoryId, 0).open();
                return;
            }
            plugin.shop().saveProduct(draft);
            Currency cur = plugin.currencies().get(draft.currency);
            plugin.activity().log("ADMIN", viewer.getName() + (isNew ? " added" : " edited") + " shop product " + draft.id + " "
                    + draft.displayName + " price " + (cur == null ? draft.price.toPlainString() : cur.format(draft.price))
                    + " unit " + draft.unit + " limit " + draft.limitPeriod + ":" + draft.limitAmount + " promos " + draft.promotions.size());
            plugin.messages().send(viewer, "admin.shop.product-saved", Vars.of("item", draft.displayName));
            plugin.sounds().success(viewer);
            new AdminProductsMenu(plugin, viewer, draft.categoryId, 0).open();
        });
    }
}
