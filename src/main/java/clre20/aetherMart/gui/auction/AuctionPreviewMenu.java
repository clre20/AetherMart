package clre20.aetherMart.gui.auction;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.auction.Auction;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.currency.CurrencyRegistry;
import clre20.aetherMart.gui.ConfirmMenu;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.gui.Tickable;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.Map;

/** 拍賣預覽：看整包內容（拿不走），購買、出價、下架；管理員可強制下架。 */
public final class AuctionPreviewMenu extends Menu implements Tickable {

    private final long auctionId;
    private final Menu back;
    private final boolean admin;
    private long confirmUntil;
    private int seenVersion = -1;

    public AuctionPreviewMenu(AetherMart plugin, Player viewer, long auctionId, Menu back) {
        this(plugin, viewer, auctionId, back, false);
    }

    public AuctionPreviewMenu(AetherMart plugin, Player viewer, long auctionId, Menu back, boolean admin) {
        super(plugin, viewer);
        this.auctionId = auctionId;
        this.back = back;
        this.admin = admin;
    }

    @Override
    public String getMenuId() {
        return admin ? "admin_auction_preview" : "auction_preview";
    }

    @Override
    protected String titleKey() {
        return "gui.auction.preview.title";
    }

    @Override
    protected Map<String, String> titleVars() {
        return Vars.of("id", auctionId);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    public void tick() {
        if (seenVersion != plugin.auctions().version() || System.currentTimeMillis() % 5000 < 1000) {
            refresh();
        }
    }

    private void goBack() {
        if (back != null) {
            back.open();
        } else {
            new AuctionBrowserMenu(plugin, viewer).open();
        }
    }

    @Override
    protected void build() {
        seenVersion = plugin.auctions().version();
        Auction a = plugin.auctions().get(auctionId);
        if (a == null) {
            plugin.messages().send(viewer, "auction.gone");
            goBack();
            return;
        }
        for (int i = 0; i < a.items.size() && i < 27; i++) {
            set(i, a.items.get(i).clone());
        }
        for (int i = 27; i < 54; i++) {
            set(i, plugin.icons().filler());
        }
        Map<String, String> vars = AuctionIcons.vars(plugin, a);
        set(49, plugin.icons().of(a.mode == Auction.Mode.FIXED ? Material.EMERALD : Material.GOLD_INGOT,
                a.mode == Auction.Mode.FIXED ? "gui.auction.preview.info-fixed" : "gui.auction.preview.info-bid", vars));
        set(45, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> goBack());
        boolean confirming = System.currentTimeMillis() < confirmUntil;
        if (admin) {
            set(51, plugin.icons().of(Material.TNT, confirming ? "gui.auction.preview.force-confirm" : "gui.auction.preview.force", vars), e -> {
                if (!viewer.hasPermission("aethermart.admin.auction")) {
                    plugin.messages().send(viewer, "common.no-permission");
                    return;
                }
                if (System.currentTimeMillis() >= confirmUntil) {
                    confirmUntil = System.currentTimeMillis() + 5000;
                    refresh();
                    return;
                }
                if (plugin.auctions().forceRemove(viewer.getName(), a)) {
                    plugin.messages().send(viewer, "admin.auction.removed", Vars.of("id", a.id, "seller", a.sellerName));
                }
                goBack();
            });
            return;
        }
        if (a.seller.equals(viewer.getUniqueId())) {
            boolean canCancel = a.phase == Auction.Phase.ACTIVE && (a.mode == Auction.Mode.FIXED || a.bids.isEmpty());
            set(51, plugin.icons().of(canCancel ? Material.HOPPER : Material.BARRIER,
                    !canCancel ? "gui.auction.preview.cannot-cancel" : confirming ? "gui.auction.preview.cancel-confirm" : "gui.auction.preview.cancel", vars), e -> {
                if (!canCancel) {
                    plugin.messages().send(viewer, "auction.cancel.has-bids");
                    return;
                }
                if (System.currentTimeMillis() >= confirmUntil) {
                    confirmUntil = System.currentTimeMillis() + 5000;
                    refresh();
                    return;
                }
                plugin.auctions().cancel(viewer, a);
                new MyListingsMenu(plugin, viewer, 0).open();
            });
            return;
        }
        if (a.mode == Auction.Mode.FIXED) {
            set(51, plugin.icons().of(Material.EMERALD_BLOCK, "gui.auction.preview.buy", vars), e -> {
                Currency c = plugin.currencies().get(a.currency);
                BigDecimal tax = plugin.auctions().tax(a, a.price);
                Map<String, String> cv = Vars.with(vars, "total", c == null ? "" : c.format(a.price.add(tax)),
                        "taxamount", c == null ? "" : c.format(tax));
                ItemStack info = AuctionIcons.icon(plugin, a, viewer, null);
                new ConfirmMenu(plugin, viewer, "gui.auction.confirm-buy", cv, info, () -> {
                    if (plugin.auctions().buy(viewer, a)) {
                        new AuctionBrowserMenu(plugin, viewer).open();
                    } else {
                        plugin.sounds().fail(viewer);
                        reopen();
                    }
                }, this::reopen).open();
            });
        } else {
            set(51, plugin.icons().of(Material.GOLD_BLOCK, a.phase == Auction.Phase.SETTLING ? "gui.auction.preview.settling" : "gui.auction.preview.bid", vars), e -> {
                if (a.phase == Auction.Phase.SETTLING) {
                    return;
                }
                Currency c = plugin.currencies().get(a.currency);
                if (c == null) {
                    return;
                }
                ask("auction.input.bid", Vars.of("id", a.id, "min", c.format(plugin.auctions().minNextBid(a))), text -> {
                    BigDecimal amount = CurrencyRegistry.parseAmount(text, c, plugin.config().maxAmount);
                    if (amount == null) {
                        plugin.messages().send(viewer, "common.bad-amount", Vars.of("decimals", c.decimals()));
                        plugin.sounds().fail(viewer);
                    } else if (!plugin.auctions().bid(viewer, a, amount)) {
                        plugin.sounds().fail(viewer);
                    }
                    reopen();
                });
            });
        }
    }
}
