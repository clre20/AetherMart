package clre20.aetherMart.gui.admin;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.auction.Auction;
import clre20.aetherMart.auction.AuctionService;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.gui.Paging;
import clre20.aetherMart.gui.Tickable;
import clre20.aetherMart.gui.auction.AuctionPreviewMenu;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** 拍賣管理：所有進行中的拍賣（預覽後可強制下架）、禁止販售、受罰名單、休市／開市。 */
public final class AdminAuctionsMenu extends Menu implements Tickable {

    static final String PERM = "aethermart.admin.auction";
    private int page;
    private int seen = -1;

    public AdminAuctionsMenu(AetherMart plugin, Player viewer, int page) {
        super(plugin, viewer);
        this.page = page;
    }

    @Override
    public String getMenuId() {
        return "admin_auctions";
    }

    @Override
    protected String titleKey() {
        return "gui.admin.auctions.title";
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
    public void tick() {
        if (seen != plugin.auctions().version()) {
            refresh();
        }
    }

    @Override
    protected void build() {
        seen = plugin.auctions().version();
        List<Auction> list = plugin.auctions().browse(AuctionService.Sort.NEWEST, null, null, null);
        page = Paging.clamp(page, list.size());
        int slot = 0;
        for (Auction a : Paging.slice(list, page)) {
            ItemStack first = a.items.isEmpty() ? new ItemStack(Material.CHEST) : a.items.get(0);
            ItemStack icon = plugin.icons().withLore(first, plugin.messages().list("gui.admin.auctions.entry"),
                    Vars.of("id", a.id, "seller", a.sellerName, "count", a.totalItems, "bids", a.bids.size(), "phase", a.phase.name()));
            set(slot++, icon, e -> {
                if (allowed(plugin, viewer)) {
                    new AuctionPreviewMenu(plugin, viewer, a.id, this, true).open();
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
        set(46, plugin.icons().of(Material.STRUCTURE_VOID, "gui.admin.auctions.banned", Vars.of("count", plugin.banned().entries().size())), e -> {
            if (allowed(plugin, viewer)) {
                new BannedItemsMenu(plugin, viewer, 0).open();
            }
        });
        set(47, plugin.icons().of(Material.IRON_BARS, "gui.admin.auctions.penalties"), e -> {
            if (allowed(plugin, viewer)) {
                new PenaltyMenu(plugin, viewer).open();
            }
        });
        boolean paused = plugin.auctions().paused();
        set(48, plugin.icons().of(paused ? Material.RED_CONCRETE : Material.LIME_CONCRETE, paused ? "gui.admin.auctions.resume" : "gui.admin.auctions.pause"), e -> {
            if (!allowed(plugin, viewer)) {
                return;
            }
            plugin.auctions().setPaused(!plugin.auctions().paused(), viewer.getName());
            plugin.messages().send(viewer, plugin.auctions().paused() ? "admin.auction.paused" : "admin.auction.resumed");
            refresh();
        });
        set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new AdminHomeMenu(plugin, viewer).open());
        if (page < Paging.pages(list.size()) - 1) {
            set(53, plugin.icons().of(Material.ARROW, "gui.common.next", Vars.of("page", page + 2)), e -> {
                page++;
                refresh();
            });
        }
    }
}
