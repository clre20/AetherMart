package clre20.aetherMart.gui.shop;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.gui.HubMenu;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.gui.Paging;
import clre20.aetherMart.shop.ShopModel;
import clre20.aetherMart.util.Texts;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

/** 系統商店主頁：分類列表。 */
public final class ShopCategoriesMenu extends Menu {

    private int page;

    public ShopCategoriesMenu(AetherMart plugin, Player viewer, int page) {
        super(plugin, viewer);
        this.page = page;
    }

    @Override
    public String getMenuId() {
        return "shop_categories";
    }

    @Override
    protected String titleKey() {
        return "gui.shop.categories.title";
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void build() {
        List<ShopModel.Category> cats = plugin.shop().categories();
        page = Paging.clamp(page, cats.size());
        int slot = 0;
        for (ShopModel.Category c : Paging.slice(cats, page)) {
            set(slot++, categoryIcon(plugin, c, "gui.shop.categories.entry"), e -> new ShopProductsMenu(plugin, viewer, c.id, 0).open());
        }
        if (cats.isEmpty()) {
            set(22, plugin.icons().of(Material.BARRIER, "gui.shop.categories.empty"));
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
        set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back-hub"), e -> new HubMenu(plugin, viewer).open());
        if (page < Paging.pages(cats.size()) - 1) {
            set(53, plugin.icons().of(Material.ARROW, "gui.common.next"), e -> {
                page++;
                refresh();
            });
        }
    }

    static ItemStack categoryIcon(AetherMart plugin, ShopModel.Category c, String loreKey) {
        ItemStack item = new ItemStack(c.icon == null ? Material.CHEST : c.icon);
        ItemMeta meta = item.getItemMeta();
        Map<String, String> vars = Vars.of("count", c.products.size(), "name", Texts.stripCodes(c.name));
        meta.displayName(Texts.item(c.name, Map.of()));
        meta.lore(Texts.itemLines(plugin.messages().list(loreKey + ".lore"), vars));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }
}
