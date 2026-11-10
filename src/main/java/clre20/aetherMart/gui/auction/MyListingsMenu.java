package clre20.aetherMart.gui.auction;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.auction.Auction;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.gui.Paging;
import clre20.aetherMart.gui.Tickable;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** 我的上架（可提前下架）＋我出價中的競標（標示是否領先）。 */
public final class MyListingsMenu extends Menu implements Tickable {

    private int page;
    private int ticks;

    public MyListingsMenu(AetherMart plugin, Player viewer, int page) {
        super(plugin, viewer);
        this.page = page;
    }

    @Override
    public String getMenuId() {
        return "auction_mine";
    }

    @Override
    protected String titleKey() {
        return "gui.auction.mine.title";
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    public void tick() {
        if (++ticks % 5 == 0) {
            refresh();
        }
    }

    @Override
    protected void build() {
        List<Auction> list = new ArrayList<>(plugin.auctions().listedBy(viewer.getUniqueId()));
        for (Auction a : plugin.auctions().biddingBy(viewer.getUniqueId())) {
            if (!list.contains(a)) {
                list.add(a);
            }
        }
        page = Paging.clamp(page, list.size());
        int slot = 0;
        for (Auction a : Paging.slice(list, page)) {
            set(slot++, AuctionIcons.icon(plugin, a, viewer, "gui.auction.entry.click"), e -> new AuctionPreviewMenu(plugin, viewer, a.id, this).open());
        }
        if (list.isEmpty()) {
            set(22, plugin.icons().of(Material.BARRIER, "gui.auction.mine.empty"));
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
        set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new AuctionHomeMenu(plugin, viewer).open());
        if (page < Paging.pages(list.size()) - 1) {
            set(53, plugin.icons().of(Material.ARROW, "gui.common.next", Vars.of("page", page + 2)), e -> {
                page++;
                refresh();
            });
        }
    }
}
