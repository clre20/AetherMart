package clre20.aetherMart.gui.admin;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.shop.ShopModel;
import clre20.aetherMart.util.Texts;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

/** 分類設定：改名、圖示（點背包物品）、排序、商品列表、刪除（點兩下）。 */
public final class CategoryEditMenu extends Menu {

    private final String categoryId;
    private boolean pickingIcon;
    private long deleteUntil;

    public CategoryEditMenu(AetherMart plugin, Player viewer, String categoryId) {
        super(plugin, viewer);
        this.categoryId = categoryId;
    }

    @Override
    public String getMenuId() {
        return "admin_shop_category";
    }

    @Override
    protected String titleKey() {
        return "gui.admin.category.title";
    }

    @Override
    protected int rows() {
        return 3;
    }

    private ShopModel.Category cat() {
        return plugin.shop().category(categoryId);
    }

    @Override
    protected void build() {
        ShopModel.Category c = cat();
        if (c == null) {
            new AdminCategoriesMenu(plugin, viewer, 0).open();
            return;
        }
        for (int i = 0; i < 27; i++) {
            set(i, plugin.icons().filler());
        }
        Map<String, String> vars = Vars.of("name", Texts.stripCodes(c.name), "count", c.products.size(), "id", c.id);
        ItemStack preview = new ItemStack(c.icon);
        ItemMeta meta = preview.getItemMeta();
        meta.displayName(Texts.item(c.name, Map.of()));
        preview.setItemMeta(meta);
        set(4, preview);
        set(10, plugin.icons().of(Material.NAME_TAG, "gui.admin.category.rename", vars), e -> {
            if (!AdminCategoriesMenu.allowed(plugin, viewer)) {
                return;
            }
            ask("admin.input.category-name", Map.of(), text -> {
                ShopModel.Category cc = cat();
                String name = Texts.sanitizeColored(text, 48);
                if (cc != null && viewer.hasPermission(AdminCategoriesMenu.PERM) && !Texts.stripCodes(name).isBlank()) {
                    plugin.activity().log("ADMIN", viewer.getName() + " renamed shop category " + cc.id + " to " + Texts.stripCodes(name));
                    cc.name = name;
                    plugin.shop().markDirty();
                }
                reopen();
            });
        });
        set(12, plugin.icons().glow(plugin.icons().of(Material.ITEM_FRAME, pickingIcon ? "gui.admin.category.icon-picking" : "gui.admin.category.icon", vars), pickingIcon), e -> {
            pickingIcon = !pickingIcon;
            refresh();
        });
        set(13, plugin.icons().of(Material.CHEST, "gui.admin.category.products", vars), e -> {
            if (AdminCategoriesMenu.allowed(plugin, viewer)) {
                new AdminProductsMenu(plugin, viewer, categoryId, 0).open();
            }
        });
        set(14, plugin.icons().of(Material.SPECTRAL_ARROW, "gui.admin.common.move-up"), e -> {
            if (AdminCategoriesMenu.allowed(plugin, viewer)) {
                plugin.shop().moveCategory(c, -1);
                refresh();
            }
        });
        set(15, plugin.icons().of(Material.ARROW, "gui.admin.common.move-down"), e -> {
            if (AdminCategoriesMenu.allowed(plugin, viewer)) {
                plugin.shop().moveCategory(c, 1);
                refresh();
            }
        });
        set(22, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new AdminCategoriesMenu(plugin, viewer, 0).open());
        boolean confirming = System.currentTimeMillis() < deleteUntil;
        set(26, plugin.icons().of(Material.LAVA_BUCKET, confirming ? "gui.admin.category.delete-confirm" : "gui.admin.category.delete", vars), e -> {
            if (!AdminCategoriesMenu.allowed(plugin, viewer)) {
                return;
            }
            if (System.currentTimeMillis() >= deleteUntil) {
                deleteUntil = System.currentTimeMillis() + 5000;
                refresh();
                return;
            }
            plugin.shop().removeCategory(c);
            plugin.activity().log("ADMIN", viewer.getName() + " deleted shop category " + c.id + " " + Texts.stripCodes(c.name)
                    + " with " + c.products.size() + " products");
            plugin.messages().send(viewer, "admin.shop.category-deleted", vars);
            new AdminCategoriesMenu(plugin, viewer, 0).open();
        });
    }

    @Override
    protected void onBottomClick(InventoryClickEvent e) {
        ItemStack it = e.getCurrentItem();
        ShopModel.Category c = cat();
        if (!pickingIcon || it == null || it.getType().isAir() || c == null || !it.getType().isItem()) {
            return;
        }
        if (!AdminCategoriesMenu.allowed(plugin, viewer)) {
            return;
        }
        c.icon = it.getType();
        pickingIcon = false;
        plugin.shop().markDirty();
        refresh();
    }
}
