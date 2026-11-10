package clre20.aetherMart.gui.auction;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.gui.HubMenu;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Map;

/** 拍賣主頁：拍賣場、建立拍賣、我的上架、領取箱、我的交易紀錄。 */
public final class AuctionHomeMenu extends Menu {

    public AuctionHomeMenu(AetherMart plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    public String getMenuId() {
        return "auction_home";
    }

    @Override
    protected String titleKey() {
        return "gui.auction.home.title";
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
        String state = plain(plugin.auctions().paused() ? "gui.auction.home.state-paused" : "gui.auction.home.state-open", Map.of());
        set(4, plugin.icons().of(Material.PAPER, "gui.auction.home.history"), e -> new HistoryMenu(plugin, viewer, null, 0).open());
        set(10, plugin.icons().of(Material.CHEST, "gui.auction.home.browse", Vars.of("count", plugin.auctions().all().size(), "state", state)),
                e -> new AuctionBrowserMenu(plugin, viewer).open());
        set(12, plugin.icons().of(Material.ANVIL, "gui.auction.home.create", Vars.of("used", plugin.auctions().listedBy(viewer.getUniqueId()).size(),
                "limit", plugin.auctions().listingLimit(viewer))), e -> {
            if (!viewer.hasPermission("aethermart.auction.sell")) {
                plugin.messages().send(viewer, "common.no-permission");
                return;
            }
            new AuctionCreateMenu(plugin, viewer, null).open();
        });
        set(14, plugin.icons().of(Material.BOOK, "gui.auction.home.mine", Vars.of("count", plugin.auctions().listedBy(viewer.getUniqueId()).size(),
                "bidding", plugin.auctions().biddingBy(viewer.getUniqueId()).size())), e -> new MyListingsMenu(plugin, viewer, 0).open());
        set(16, plugin.icons().of(Material.ENDER_CHEST, "gui.auction.home.claims", Vars.of("count", plugin.claims().count(viewer.getUniqueId()))),
                e -> new ClaimBoxMenu(plugin, viewer, 0).open());
        set(22, plugin.icons().of(Material.OAK_DOOR, "gui.common.back-hub"), e -> new HubMenu(plugin, viewer).open());
    }
}
