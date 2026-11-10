package clre20.aetherMart.gui.auction;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.auction.Auction;
import clre20.aetherMart.auction.AuctionService;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.gui.Paging;
import clre20.aetherMart.gui.Tickable;
import clre20.aetherMart.util.Texts;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 拍賣場：排序、篩選模式與幣別、名稱搜尋、分頁。 */
public final class AuctionBrowserMenu extends Menu implements Tickable {

    private AuctionService.Sort sort = AuctionService.Sort.NEWEST;
    private Auction.Mode mode;
    private String currency;
    private String search;
    private int page;
    private int seenVersion = -1;
    private int ticks;

    public AuctionBrowserMenu(AetherMart plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    public String getMenuId() {
        return "auction_browser";
    }

    @Override
    protected String titleKey() {
        return "gui.auction.browser.title";
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    public void tick() {
        ticks++;
        if (seenVersion != plugin.auctions().version() || ticks % 5 == 0) {
            refresh();
        }
    }

    @Override
    protected void build() {
        seenVersion = plugin.auctions().version();
        List<Auction> list = plugin.auctions().browse(sort, mode, currency, search);
        page = Paging.clamp(page, list.size());
        int slot = 0;
        for (Auction a : Paging.slice(list, page)) {
            set(slot++, AuctionIcons.icon(plugin, a, viewer, "gui.auction.entry.click"), e -> new AuctionPreviewMenu(plugin, viewer, a.id, this).open());
        }
        if (list.isEmpty()) {
            set(22, plugin.icons().of(Material.BARRIER, "gui.auction.browser.empty"));
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
        set(46, plugin.icons().of(Material.HOPPER, "gui.auction.browser.sort", Vars.of("value",
                plain("gui.auction.sort." + sort.name().toLowerCase(), Map.of()))), e -> {
            sort = AuctionService.Sort.values()[(sort.ordinal() + 1) % AuctionService.Sort.values().length];
            page = 0;
            refresh();
        });
        set(47, plugin.icons().of(Material.COMPASS, "gui.auction.browser.mode", Vars.of("value",
                plain(mode == null ? "gui.auction.filter.all" : mode == Auction.Mode.FIXED ? "auction.mode.fixed" : "auction.mode.bid", Map.of()))), e -> {
            mode = mode == null ? Auction.Mode.FIXED : mode == Auction.Mode.FIXED ? Auction.Mode.BID : null;
            page = 0;
            refresh();
        });
        Currency cur = currency == null ? null : plugin.currencies().get(currency);
        set(48, plugin.icons().of(Material.GOLD_NUGGET, "gui.auction.browser.currency", Vars.of("value",
                cur == null ? plain("gui.auction.filter.all", Map.of()) : cur.name())), e -> {
            List<String> ids = new ArrayList<>(plugin.market().auctionCurrencies);
            ids.removeIf(id -> plugin.currencies().get(id) == null);
            int i = currency == null ? -1 : ids.indexOf(currency);
            currency = i + 1 >= ids.size() ? null : ids.get(i + 1);
            page = 0;
            refresh();
        });
        set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new AuctionHomeMenu(plugin, viewer).open());
        set(50, plugin.icons().of(Material.NAME_TAG, search == null ? "gui.auction.browser.search" : "gui.auction.browser.search-active",
                Vars.of("value", search == null ? "" : search)), e -> {
            if (search != null) {
                search = null;
                page = 0;
                refresh();
                return;
            }
            ask("auction.input.search", Map.of(), text -> {
                String s = Texts.sanitizeInput(text, 32);
                search = s.isEmpty() ? null : s;
                page = 0;
                reopen();
            });
        });
        set(51, plugin.icons().of(Material.BOOK, "gui.auction.browser.mine"), e -> new MyListingsMenu(plugin, viewer, 0).open());
        set(52, plugin.icons().of(Material.ENDER_CHEST, "gui.auction.browser.claims", Vars.of("count", plugin.claims().count(viewer.getUniqueId()))),
                e -> new ClaimBoxMenu(plugin, viewer, 0).open());
        if (page < Paging.pages(list.size()) - 1) {
            set(53, plugin.icons().of(Material.ARROW, "gui.common.next", Vars.of("page", page + 2)), e -> {
                page++;
                refresh();
            });
        }
    }
}
