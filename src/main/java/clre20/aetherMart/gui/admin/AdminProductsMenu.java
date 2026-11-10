package clre20.aetherMart.gui.admin;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.gui.Paging;
import clre20.aetherMart.shop.ShopModel;
import clre20.aetherMart.util.Texts;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/** 分類內的商品管理：點自己背包的物品上架（複製一份、保留完整 NBT），點商品編輯。 */
public final class AdminProductsMenu extends Menu {

    private final String categoryId;
    private int page;

    public AdminProductsMenu(AetherMart plugin, Player viewer, String categoryId, int page) {
        super(plugin, viewer);
        this.categoryId = categoryId;
        this.page = page;
    }

    @Override
    public String getMenuId() {
        return "admin_shop_products";
    }

    @Override
    protected String titleKey() {
        return "gui.admin.products.title";
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
            new AdminCategoriesMenu(plugin, viewer, 0).open();
            return;
        }
        List<ShopModel.Product> list = cat.products;
        page = Paging.clamp(page, list.size());
        int slot = 0;
        for (ShopModel.Product p : Paging.slice(list, page)) {
            Currency c = plugin.currencies().get(p.currency);
            ItemStack icon = plugin.icons().withLore(p.item, plugin.messages().list("gui.admin.products.entry"), Vars.of(
                    "price", c == null ? p.price.toPlainString() : c.format(p.price), "unit", p.unit,
                    "currency", c == null ? p.currency : c.name(),
                    "limit", p.limitPeriod == ShopModel.LimitPeriod.NONE ? plain("shop.period.none", Map.of())
                            : p.limitAmount + " / " + plain("shop.period." + p.limitPeriod.name().toLowerCase(), Map.of()),
                    "promos", p.promotions.size()));
            set(slot++, icon, e -> {
                if (AdminCategoriesMenu.allowed(plugin, viewer)) {
                    new ProductEditMenu(plugin, viewer, p.copy(), false).open();
                }
            });
        }
        for (int i = 45; i < 54; i++) {
            set(i, plugin.icons().filler());
        }
        set(47, plugin.icons().of(Material.BOOK, "gui.admin.products.help"));
        if (page > 0) {
            set(45, plugin.icons().of(Material.ARROW, "gui.common.prev", Vars.of("page", page)), e -> {
                page--;
                refresh();
            });
        }
        set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new CategoryEditMenu(plugin, viewer, categoryId).open());
        if (page < Paging.pages(list.size()) - 1) {
            set(53, plugin.icons().of(Material.ARROW, "gui.common.next", Vars.of("page", page + 2)), e -> {
                page++;
                refresh();
            });
        }
    }

    @Override
    protected void onBottomClick(InventoryClickEvent e) {
        ItemStack it = e.getCurrentItem();
        ShopModel.Category cat = plugin.shop().category(categoryId);
        if (it == null || it.getType().isAir() || cat == null || !AdminCategoriesMenu.allowed(plugin, viewer)) {
            return;
        }
        for (ShopModel.Product p : cat.products) {
            if (p.item.isSimilar(it)) {
                plugin.messages().send(viewer, "admin.shop.product-exists");
                new ProductEditMenu(plugin, viewer, p.copy(), false).open();
                return;
            }
        }
        if (cat.products.size() >= 2000) {
            plugin.messages().send(viewer, "admin.shop.too-many");
            return;
        }
        ItemStack one = it.clone();
        one.setAmount(1);
        Currency def = plugin.currencies().getDefault();
        ShopModel.Product draft = new ShopModel.Product(plugin.shop().newId(), categoryId, one, def.id(),
                plugin.market().shopMin(def.id()), 1);
        draft.displayName = plugin.names().name(one);
        new ProductEditMenu(plugin, viewer, draft, true).open();
    }
}
