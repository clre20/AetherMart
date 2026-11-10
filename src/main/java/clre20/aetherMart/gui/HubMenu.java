package clre20.aetherMart.gui;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.gui.auction.AuctionHomeMenu;
import clre20.aetherMart.gui.auction.ClaimBoxMenu;
import clre20.aetherMart.gui.shop.ShopCategoriesMenu;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** 分流介面：系統商店／玩家拍賣／領取箱／個人設定。 */
public final class HubMenu extends Menu {

    public HubMenu(AetherMart plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    public String getMenuId() {
        return "hub";
    }

    @Override
    protected String titleKey() {
        return "gui.hub.title";
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
        int claims = plugin.claims().count(viewer.getUniqueId());
        set(10, plugin.icons().of(Material.CHEST, "gui.hub.shop", Vars.of("count", plugin.shop().categories().size())),
                e -> new ShopCategoriesMenu(plugin, viewer, 0).open());
        set(12, plugin.icons().of(Material.GOLD_INGOT, "gui.hub.auction", Vars.of("count", plugin.auctions().all().size())),
                e -> new AuctionHomeMenu(plugin, viewer).open());
        set(14, plugin.icons().glow(plugin.icons().of(Material.ENDER_CHEST, "gui.hub.claims", Vars.of("count", claims)), claims > 0),
                e -> new ClaimBoxMenu(plugin, viewer, 0).open());
        set(16, plugin.icons().of(Material.COMPARATOR, "gui.hub.settings"), e -> new SettingsMenu(plugin, viewer).open());
        set(22, plugin.icons().of(Material.BARRIER, "gui.common.close"), e -> viewer.closeInventory());
    }
}
