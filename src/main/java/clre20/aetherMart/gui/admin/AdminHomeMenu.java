package clre20.aetherMart.gui.admin;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.gui.auction.HistoryMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** 管理主介面：商店管理、拍賣管理、交易紀錄；沒有權限的按鈕變灰。 */
public final class AdminHomeMenu extends Menu {

    public AdminHomeMenu(AetherMart plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    public String getMenuId() {
        return "admin_home";
    }

    @Override
    protected String titleKey() {
        return "gui.admin.home.title";
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, plugin.icons().filler());
        }
        button(11, "aethermart.admin.shop", Material.CHEST, "gui.admin.home.shop", () -> new AdminCategoriesMenu(plugin, viewer, 0).open());
        button(13, "aethermart.admin.auction", Material.GOLD_BLOCK, "gui.admin.home.auction", () -> new AdminAuctionsMenu(plugin, viewer, 0).open());
        button(15, "aethermart.admin.log", Material.BOOK, "gui.admin.home.log", () -> new HistoryMenu(plugin, viewer, true, null, null, 0).open());
        set(22, plugin.icons().of(Material.BARRIER, "gui.common.close"), e -> viewer.closeInventory());
    }

    private void button(int slot, String perm, Material m, String key, Runnable open) {
        if (!viewer.hasPermission(perm)) {
            set(slot, plugin.icons().of(Material.GRAY_DYE, key + "-locked"));
            return;
        }
        set(slot, plugin.icons().of(m, key), e -> {
            if (!viewer.hasPermission(perm)) {
                plugin.messages().send(viewer, "common.no-permission");
                return;
            }
            open.run();
        });
    }
}
