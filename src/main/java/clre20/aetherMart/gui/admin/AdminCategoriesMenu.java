package clre20.aetherMart.gui.admin;

import clre20.aetherMart.AetherMart;
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

/** 商店管理：分類列表、新增分類。 */
public final class AdminCategoriesMenu extends Menu {

    static final String PERM = "aethermart.admin.shop";
    private int page;

    public AdminCategoriesMenu(AetherMart plugin, Player viewer, int page) {
        super(plugin, viewer);
        this.page = page;
    }

    @Override
    public String getMenuId() {
        return "admin_shop_categories";
    }

    @Override
    protected String titleKey() {
        return "gui.admin.categories.title";
    }

    @Override
    protected int rows() {
        return 6;
    }

    static boolean allowed(AetherMart plugin, Player p) {
        if (!p.hasPermission(PERM)) {
            plugin.messages().send(p, "common.no-permission");
            p.closeInventory();
            return false;
        }
        return true;
    }

    @Override
    protected void build() {
        List<ShopModel.Category> cats = plugin.shop().categories();
        page = Paging.clamp(page, cats.size());
        int slot = 0;
        for (ShopModel.Category c : Paging.slice(cats, page)) {
            ItemStack icon = new ItemStack(c.icon);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Texts.item(c.name, Map.of()));
            meta.lore(Texts.itemLines(plugin.messages().list("gui.admin.categories.entry.lore"),
                    Vars.of("count", c.products.size(), "id", c.id)));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            icon.setItemMeta(meta);
            set(slot++, icon, e -> {
                if (allowed(plugin, viewer)) {
                    new CategoryEditMenu(plugin, viewer, c.id).open();
                }
            });
        }
        for (int i = 45; i < 54; i++) {
            set(i, plugin.icons().filler());
        }
        if (page > 0) {
            set(45, plugin.icons().of(Material.ARROW, "gui.common.prev", Vars.of("page", page)), e -> {
                page--;
                refresh();
            });
        }
        set(47, plugin.icons().of(Material.LIME_DYE, "gui.admin.categories.add"), e -> {
            if (!allowed(plugin, viewer)) {
                return;
            }
            if (plugin.shop().categories().size() >= 500) {
                plugin.messages().send(viewer, "admin.shop.too-many");
                return;
            }
            ask("admin.input.category-name", Map.of(), text -> {
                if (!viewer.hasPermission(PERM)) {
                    return;
                }
                String name = Texts.sanitizeColored(text, 48);
                if (Texts.stripCodes(name).isBlank()) {
                    plugin.messages().send(viewer, "admin.shop.bad-name");
                    reopen();
                    return;
                }
                ShopModel.Category c = plugin.shop().addCategory(name);
                plugin.activity().log("ADMIN", viewer.getName() + " added shop category " + c.id + " " + Texts.stripCodes(name));
                new CategoryEditMenu(plugin, viewer, c.id).open();
            });
        });
        set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new AdminHomeMenu(plugin, viewer).open());
        if (page < Paging.pages(cats.size()) - 1) {
            set(53, plugin.icons().of(Material.ARROW, "gui.common.next", Vars.of("page", page + 2)), e -> {
                page++;
                refresh();
            });
        }
    }
}
