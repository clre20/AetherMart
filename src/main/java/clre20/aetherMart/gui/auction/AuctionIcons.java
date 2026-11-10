package clre20.aetherMart.gui.auction;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.auction.Auction;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 拍賣項目的圖示：第一格物品＋賣家、件數、模式、價格、剩餘時間。 */
final class AuctionIcons {

    private AuctionIcons() {
    }

    static String remaining(AetherMart plugin, long ms) {
        return TimeUtil.formatDuration(ms, plugin.messages().plain("time.day", Map.of()), plugin.messages().plain("time.hour", Map.of()),
                plugin.messages().plain("time.minute", Map.of()), plugin.messages().plain("time.second", Map.of()));
    }

    static Map<String, String> vars(AetherMart plugin, Auction a) {
        Currency c = plugin.currencies().get(a.currency);
        long now = System.currentTimeMillis();
        String status = switch (a.phase) {
            case ACTIVE -> plugin.messages().plain("gui.auction.status.active", Vars.of("time", remaining(plugin, a.endsAt - now)));
            case KNOCKING -> plugin.messages().plain("gui.auction.status.knocking", Vars.of("n", a.knock + 1,
                    "count", plugin.market().knockCount, "time", remaining(plugin, a.nextKnockAt - now)));
            case SETTLING -> plugin.messages().plain("gui.auction.status.settling", Map.of());
        };
        Auction.Bid h = a.highest();
        return Vars.of("id", a.id, "seller", a.sellerName, "count", a.totalItems, "slots", a.items.size(),
                "mode", plugin.messages().plain(a.mode == Auction.Mode.FIXED ? "auction.mode.fixed" : "auction.mode.bid", Map.of()),
                "price", c == null ? a.currentPrice().toPlainString() : c.format(a.currentPrice()),
                "start", c == null ? a.price.toPlainString() : c.format(a.price),
                "currency", c == null ? a.currency : c.name(), "status", status,
                "bidder", h == null ? plugin.messages().plain("gui.auction.no-bidder", Map.of()) : h.name(),
                "bids", a.bids.size(),
                "tax", plugin.market().auctionTax(a.currency).stripTrailingZeros().toPlainString(),
                "min", c == null ? "" : c.format(plugin.auctions().minNextBid(a)));
    }

    static ItemStack icon(AetherMart plugin, Auction a, Player viewer, String extraKey) {
        ItemStack first = a.items.isEmpty() ? new ItemStack(Material.CHEST) : a.items.get(0).clone();
        List<String> lore = new ArrayList<>(plugin.messages().list(a.mode == Auction.Mode.FIXED ? "gui.auction.entry.fixed" : "gui.auction.entry.bid"));
        if (a.seller.equals(viewer.getUniqueId())) {
            lore.addAll(plugin.messages().list("gui.auction.entry.own"));
        } else if (a.highest() != null && a.highest().bidder().equals(viewer.getUniqueId())) {
            lore.addAll(plugin.messages().list("gui.auction.entry.leading"));
        } else if (a.hasBidFrom(viewer.getUniqueId())) {
            lore.addAll(plugin.messages().list("gui.auction.entry.outbid"));
        }
        if (extraKey != null) {
            lore.addAll(plugin.messages().list(extraKey));
        }
        ItemStack icon = plugin.icons().withLore(first, lore, vars(plugin, a));
        icon.setAmount(Math.max(1, Math.min(icon.getMaxStackSize(), a.items.isEmpty() ? 1 : a.items.get(0).getAmount())));
        return icon;
    }
}
