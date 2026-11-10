package clre20.aetherMart.gui.shop;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.gui.Paging;
import clre20.aetherMart.player.PlayerData;
import clre20.aetherMart.shop.ShopModel;
import clre20.aetherMart.util.Texts;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 分類內的商品列表。 */
public final class ShopProductsMenu extends Menu {

    private final String categoryId;
    private int page;

    public ShopProductsMenu(AetherMart plugin, Player viewer, String categoryId, int page) {
        super(plugin, viewer);
        this.categoryId = categoryId;
        this.page = page;
    }

    @Override
    public String getMenuId() {
        return "shop_products";
    }

    @Override
    protected String titleKey() {
        return "gui.shop.products.title";
    }

    @Override
    protected Map<String, String> titleVars() {
        ShopModel.Category c = plugin.shop().category(categoryId);
        return Vars.of("category", c == null ? "" : Texts.stripCodes(c.name));
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void build() {
        ShopModel.Category cat = plugin.shop().category(categoryId);
        if (cat == null) {
            new ShopCategoriesMenu(plugin, viewer, 0).open();
            return;
        }
        List<ShopModel.Product> list = cat.products;
        page = Paging.clamp(page, list.size());
        int slot = 0;
        for (ShopModel.Product p : Paging.slice(list, page)) {
            set(slot++, productIcon(plugin, viewer, p), e -> new ShopQuantityMenu(plugin, viewer, p.id, 1).open());
        }
        if (list.isEmpty()) {
            set(22, plugin.icons().of(Material.BARRIER, "gui.shop.products.empty"));
        }
        for (int i = 45; i < 54; i++) {
            set(i, plugin.icons().filler());
        }
        if (page > 0) {
            set(45, plugin.icons().of(Material.ARROW, "gui.common.prev"), e -> {
                page--;
                refresh();
            });
        }
        set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new ShopCategoriesMenu(plugin, viewer, 0).open());
        if (page < Paging.pages(list.size()) - 1) {
            set(53, plugin.icons().of(Material.ARROW, "gui.common.next"), e -> {
                page++;
                refresh();
            });
        }
    }

    static ItemStack productIcon(AetherMart plugin, Player viewer, ShopModel.Product p) {
        long now = System.currentTimeMillis();
        Currency c = plugin.currencies().get(p.currency);
        BigDecimal unit = plugin.shop().unitPrice(p, now);
        List<String> lore = new ArrayList<>(plugin.messages().list("gui.shop.products.price"));
        Map<String, String> vars = Vars.of("price", c == null ? unit.toPlainString() : c.format(unit), "unit", p.unit,
                "currency", c == null ? p.currency : c.name(), "original", c == null ? p.price.toPlainString() : c.format(p.price));
        ShopModel.Promotion promo = p.activePromotion(now);
        if (promo != null) {
            lore.addAll(plugin.messages().list("gui.shop.products.promo"));
            vars.put("ends", TimeUtil.format(promo.end(), plugin.config().zone, TimeUtil.DATE_TIME_SHORT));
            vars.put("percent", promo.percent() != null && promo.percent().signum() > 0 ? promo.percent().stripTrailingZeros().toPlainString() : "");
        }
        if (p.limitPeriod != ShopModel.LimitPeriod.NONE && p.limitAmount > 0) {
            PlayerData d = plugin.players().get(viewer.getUniqueId());
            int left = d == null ? 0 : plugin.shop().remaining(d, p, now);
            lore.addAll(plugin.messages().list("gui.shop.products.limit"));
            vars.put("left", String.valueOf(left));
            vars.put("limit", String.valueOf(p.limitAmount));
            vars.put("period", plugin.messages().plain("shop.period." + p.limitPeriod.name().toLowerCase(), Map.of()));
        }
        if (c == null || !c.available()) {
            lore.addAll(plugin.messages().list("gui.shop.products.paused"));
        } else {
            lore.addAll(plugin.messages().list("gui.shop.products.click"));
        }
        ItemStack icon = plugin.icons().withLore(p.item, lore, vars);
        icon.setAmount(Math.max(1, Math.min(icon.getMaxStackSize(), p.unit)));
        return icon;
    }
}
