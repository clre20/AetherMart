package clre20.aetherMart.gui.shop;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.shop.ShopModel;
import clre20.aetherMart.shop.ShopService;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 數量頁：選份數、顯示商品金額、稅、應付總額與餘額，按確認購買。 */
public final class ShopQuantityMenu extends Menu {

    private final String productId;
    private int portions;

    public ShopQuantityMenu(AetherMart plugin, Player viewer, String productId, int portions) {
        super(plugin, viewer);
        this.productId = productId;
        this.portions = Math.max(1, portions);
    }

    @Override
    public String getMenuId() {
        return "shop_quantity";
    }

    @Override
    protected String titleKey() {
        return "gui.shop.quantity.title";
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected void build() {
        ShopModel.Product p = plugin.shop().product(productId);
        if (p == null) {
            plugin.messages().send(viewer, "shop.gone");
            new ShopCategoriesMenu(plugin, viewer, 0).open();
            return;
        }
        for (int i = 0; i < 27; i++) {
            set(i, plugin.icons().filler());
        }
        Currency c = plugin.currencies().get(p.currency);
        ShopService.Quote q = plugin.shop().quote(p, portions, System.currentTimeMillis());
        BigDecimal balance = c == null || !c.available() ? BigDecimal.ZERO : c.provider().balance(viewer);
        Map<String, String> vars = Vars.of("item", p.displayName, "portions", portions, "items", q.items(),
                "unit", c == null ? "" : c.format(q.unitPrice()), "subtotal", c == null ? "" : c.format(q.subtotal()),
                "tax", c == null ? "" : c.format(q.tax()), "percent", plugin.market().shopTax(p.currency).stripTrailingZeros().toPlainString(),
                "total", c == null ? "" : c.format(q.total()), "balance", c == null ? "" : c.format(c.round(balance)));
        ItemStack info = plugin.icons().withLore(p.item, plugin.messages().list("gui.shop.quantity.info"), vars);
        info.setAmount(Math.max(1, Math.min(info.getMaxStackSize(), q.items())));
        set(4, info);
        List<Integer> buttons = plugin.market().quantityButtons;
        for (int i = 0; i < buttons.size(); i++) {
            int n = buttons.get(i);
            ItemStack icon = plugin.icons().glow(plugin.icons().of(n == portions ? Material.LIME_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE,
                    "gui.shop.quantity.option", Vars.of("portions", n, "items", n * p.unit)), n == portions);
            icon.setAmount(Math.max(1, Math.min(64, n)));
            set(10 + i, icon, e -> {
                portions = n;
                refresh();
            });
        }
        set(18, plugin.icons().of(Material.NAME_TAG, "gui.shop.quantity.custom"), e -> ask("shop.input.quantity", Vars.of("item", p.displayName), text -> {
            try {
                int n = Integer.parseInt(text.trim());
                if (n < 1 || n * (long) p.unit > 2304) {
                    throw new NumberFormatException();
                }
                portions = n;
            } catch (NumberFormatException ex) {
                plugin.messages().send(viewer, "shop.input.bad-quantity", Vars.of("max", Math.max(1, 2304 / p.unit)));
            }
            reopen();
        }));
        set(22, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new ShopProductsMenu(plugin, viewer, p.categoryId, 0).open());
        set(26, plugin.icons().of(Material.EMERALD_BLOCK, "gui.shop.quantity.confirm", vars), e -> {
            if (!plugin.shop().buy(viewer, p, portions)) {
                plugin.sounds().fail(viewer);
            }
            refresh();
        });
    }
}
