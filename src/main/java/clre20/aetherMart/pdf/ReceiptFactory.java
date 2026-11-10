package clre20.aetherMart.pdf;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.auction.Auction;
import clre20.aetherMart.config.PdfConfig;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.player.PlayerData;
import clre20.aetherMart.shop.ShopModel;
import clre20.aetherMart.shop.ShopService;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 依各種交易建立明細內容，並送進 Discord 通知佇列。在主執行緒呼叫。 */
public final class ReceiptFactory {

    private final AetherMart plugin;

    public ReceiptFactory(AetherMart plugin) {
        this.plugin = plugin;
    }

    private PdfConfig pdf() {
        return plugin.pdf();
    }

    private String t(String path) {
        return pdf().text("text." + path);
    }

    private String t(String path, Map<String, String> vars) {
        String s = t(path);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    private String time(long ms) {
        return TimeUtil.format(ms, plugin.config().zone, TimeUtil.DATE_TIME);
    }

    private Receipt base(String kind, String number, long time) {
        Receipt r = new Receipt();
        r.kind = kind;
        r.number = number;
        r.time = time;
        r.stampDate = TimeUtil.format(time, plugin.config().zone, java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        r.itemsTitle = t("sections.items");
        r.summaryTime = TimeUtil.format(time, plugin.config().zone, java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        return r;
    }

    private void field(Receipt r, String key, String value) {
        r.fields.add(new Receipt.Field(t("fields." + key), value == null ? "" : value, key.equals("id")));
    }

    private void lines(Receipt r, List<ItemStack> items) {
        String dur = plugin.messages().plain("item.durability", Map.of());
        for (ItemStack i : items) {
            r.lines.add(new Receipt.Line(plugin.names().name(i), i.getType().getKey().toString(), i.getAmount(),
                    plugin.names().remark(i, dur)));
        }
    }

    private String countText(List<ItemStack> items) {
        int n = 0;
        for (ItemStack i : items) {
            n += i.getAmount();
        }
        return t("values.items-count", Vars.of("slots", items.size(), "amount", n));
    }

    private String percent(BigDecimal p) {
        return p.stripTrailingZeros().toPlainString();
    }

    private String mode(Auction a) {
        return t(a.mode == Auction.Mode.FIXED ? "modes.fixed" : "modes.bid");
    }

    // ------------------------------------------------------------------

    public void shopPurchase(Player p, ShopModel.Product product, ShopService.Quote q, Currency c, BigDecimal balance, String no, long now) {
        Receipt r = base("shop-purchase", no, now);
        field(r, "id", no);
        field(r, "time", time(now));
        field(r, "type", t("trade-types.shop-buy"));
        ShopModel.Category cat = plugin.shop().category(product.categoryId);
        field(r, "category", cat == null ? "" : clre20.aetherMart.util.Texts.stripCodes(cat.name));
        field(r, "buyer", p.getName());
        field(r, "seller", t("values.system-shop"));
        field(r, "currency", c.name());
        field(r, "unit", t("values.unit-count", Vars.of("unit", product.unit)));
        ItemStack one = product.item.clone();
        one.setAmount(q.items());
        lines(r, List.of(one));
        r.totals.add(new Receipt.Total(t("totals.unit-price", Vars.of("unit", product.unit)), c.format(q.unitPrice()), null));
        if (q.promotion() != null) {
            r.totals.add(new Receipt.Total(t("totals.promo", Vars.of("original", c.format(product.price))), "", null));
        }
        r.totals.add(new Receipt.Total(t("totals.quantity"), t("values.pieces", Vars.of("count", q.items())), null));
        r.totals.add(new Receipt.Total(t("totals.subtotal"), c.format(q.subtotal()), null));
        r.totals.add(new Receipt.Total(t("totals.tax", Vars.of("percent", percent(plugin.market().shopTax(c.id())))), c.format(q.tax()), null));
        r.totals.add(new Receipt.Total(t("totals.balance-after"), c.format(c.round(balance)), null));
        r.totals.add(new Receipt.Total(t("totals.paid"), c.format(q.total()), "total"));
        r.notes.add(t("notes.paid-from", Vars.of("currency", c.name())));
        r.notes.add(t("notes.to-inventory"));
        r.summaryType = t("trade-types.shop-buy");
        r.summaryAuction = t("values.none");
        r.summaryAmount = c.format(q.total());
        flow(r, c, q.total());
        Map<String, String> vars = Vars.of("player", p.getName(), "item", product.displayName, "amount", q.items(),
                "price", c.format(q.total()), "receipt", no);
        plugin.notifier().personal(p.getUniqueId(), p.getName(), PlayerData.N_PURCHASE, "discord.dm.shop-purchase", vars, r);
        plugin.notifier().admin("discord.admin.shop-purchase", vars);
    }

    public void auctionListed(Auction a) {
        Currency c = plugin.currencies().get(a.currency);
        Receipt r = base("auction-listed", a.listReceipt, a.created);
        field(r, "id", a.listReceipt);
        field(r, "listed-at", time(a.created));
        field(r, "type", t(a.mode == Auction.Mode.FIXED ? "trade-types.listed-fixed" : "trade-types.listed-bid"));
        field(r, "auction-id", "#A-" + a.id);
        field(r, "seller", a.sellerName);
        field(r, "ends-at", time(a.endsAt));
        field(r, "currency", c.name());
        field(r, "count", countText(a.items));
        lines(r, a.items);
        BigDecimal pct = plugin.market().auctionTax(a.currency);
        r.totals.add(new Receipt.Total(t(a.mode == Auction.Mode.FIXED ? "totals.sale-price" : "totals.start-price"), c.format(a.price), null));
        r.totals.add(new Receipt.Total(t("totals.buyer-tax", Vars.of("percent", percent(pct))), c.format(c.tax(a.price, pct)), null));
        r.totals.add(new Receipt.Total(t("totals.expected"), c.format(a.price), "total"));
        r.notes.add(t("notes.expire-return"));
        r.notes.add(t("notes.cancel-hint"));
        if (a.mode == Auction.Mode.BID) {
            r.notes.add(t("notes.knock-rule", Vars.of("minutes", plugin.market().knockIntervalMs / 60000, "count", plugin.market().knockCount)));
        }
        r.summaryType = t(a.mode == Auction.Mode.FIXED ? "trade-types.listed-fixed" : "trade-types.listed-bid");
        r.summaryAuction = "#A-" + a.id;
        r.summaryAmount = c.format(a.price);
        Map<String, String> vars = Vars.of("player", a.sellerName, "id", a.id, "mode", mode(a), "price", c.format(a.price),
                "items", plugin.auctions().summary(a), "ends", time(a.endsAt), "receipt", a.listReceipt);
        plugin.notifier().personal(a.seller, a.sellerName, PlayerData.N_LISTED, "discord.dm.listed", vars, r);
    }

    public void outbid(Auction a, Auction.Bid prev, BigDecimal newAmount, BigDecimal nextMin) {
        Currency c = plugin.currencies().get(a.currency);
        long now = System.currentTimeMillis();
        String no = plugin.meta().nextReceipt(plugin.config().zone);
        Receipt r = base("auction-outbid", no, now);
        r.itemsTitle = t("sections.auction-items");
        field(r, "id", no);
        field(r, "time", time(now));
        field(r, "type", t("trade-types.outbid"));
        field(r, "auction-id", "#A-" + a.id);
        field(r, "bidder", prev.name());
        field(r, "seller", a.sellerName);
        field(r, "currency", c.name());
        long left = a.phase == Auction.Phase.KNOCKING ? a.nextKnockAt - now : a.endsAt - now;
        field(r, "remaining", duration(left));
        lines(r, a.items);
        r.totals.add(new Receipt.Total(t("totals.your-bid"), c.format(prev.amount()), null));
        r.totals.add(new Receipt.Total(t("totals.highest"), c.format(newAmount), null));
        r.totals.add(new Receipt.Total(t("totals.next-min"), c.format(nextMin), "total"));
        r.notes.add(t("notes.outbid"));
        r.notes.add(t("notes.rebid"));
        r.summaryType = t("trade-types.outbid");
        r.summaryAuction = "#A-" + a.id;
        r.summaryAmount = t("values.none");
        Map<String, String> vars = Vars.of("player", prev.name(), "id", a.id, "price", c.format(newAmount),
                "min", c.format(nextMin), "items", plugin.auctions().summary(a), "receipt", no);
        plugin.notifier().personal(prev.bidder(), prev.name(), PlayerData.N_OUTBID, "discord.dm.outbid", vars, r);
    }

    public void auctionExpired(Auction a, String no, long now) {
        Currency c = plugin.currencies().get(a.currency);
        boolean unpaid = a.mode == Auction.Mode.BID && !a.bids.isEmpty();
        Receipt r = base("auction-expired", no, now);
        r.itemsTitle = t("sections.returned-items");
        field(r, "id", no);
        field(r, "ended-at", time(now));
        String type = t(unpaid ? "trade-types.expired-unpaid" : a.mode == Auction.Mode.FIXED ? "trade-types.expired-fixed" : "trade-types.expired-bid");
        field(r, "type", type);
        field(r, "auction-id", "#A-" + a.id);
        field(r, "seller", a.sellerName);
        field(r, "bidders", t("values.people", Vars.of("count", a.candidates().size())));
        field(r, "currency", c == null ? a.currency : c.name());
        field(r, "duration", duration(a.endsAt - a.created));
        lines(r, a.items);
        String none = t("values.none");
        r.totals.add(new Receipt.Total(t(a.mode == Auction.Mode.FIXED ? "totals.sale-price" : "totals.start-price"), c == null ? a.price.toPlainString() : c.format(a.price), null));
        r.totals.add(new Receipt.Total(t("totals.highest"), a.bids.isEmpty() || c == null ? none : c.format(a.currentPrice()), null));
        r.totals.add(new Receipt.Total(t("totals.deal"), none, "total"));
        r.notes.add(t(unpaid ? "notes.unpaid" : a.mode == Auction.Mode.FIXED ? "notes.expired-fixed" : "notes.no-bids"));
        r.notes.add(t("notes.returned"));
        r.summaryType = type;
        r.summaryAuction = "#A-" + a.id;
        r.summaryAmount = none;
        Map<String, String> vars = Vars.of("player", a.sellerName, "id", a.id, "items", plugin.auctions().summary(a), "receipt", no);
        plugin.notifier().personal(a.seller, a.sellerName, PlayerData.N_EXPIRED, "discord.dm.expired", vars, r);
    }

    public void auctionRemoved(Auction a, boolean forced, String by, String no, long now) {
        Currency c = plugin.currencies().get(a.currency);
        Receipt r = base("auction-removed", no, now);
        r.itemsTitle = t("sections.returned-items");
        field(r, "id", no);
        field(r, "ended-at", time(now));
        String type = t(forced ? "trade-types.force-removed" : "trade-types.cancelled");
        field(r, "type", type);
        field(r, "auction-id", "#A-" + a.id);
        field(r, "seller", a.sellerName);
        field(r, "handler", by);
        field(r, "currency", c == null ? a.currency : c.name());
        field(r, "mode", mode(a) + (a.bids.isEmpty() ? "" : "（" + t("values.people", Vars.of("count", a.candidates().size())) + "）"));
        lines(r, a.items);
        String none = t("values.none");
        r.totals.add(new Receipt.Total(t("totals.highest"), a.bids.isEmpty() || c == null ? none : c.format(a.currentPrice()), null));
        r.totals.add(new Receipt.Total(t("totals.deal"), none, "total"));
        r.notes.add(t(forced ? "notes.forced" : "notes.cancelled"));
        r.notes.add(t("notes.returned"));
        if (forced) {
            r.notes.add(t("notes.contact-admin"));
        }
        r.summaryType = type;
        r.summaryAuction = "#A-" + a.id;
        r.summaryAmount = none;
        Map<String, String> vars = Vars.of("player", a.sellerName, "id", a.id, "admin", by, "items", plugin.auctions().summary(a), "receipt", no);
        plugin.notifier().personal(a.seller, a.sellerName, PlayerData.N_REMOVED, forced ? "discord.dm.force-removed" : "discord.dm.cancelled", vars, r);
    }

    public void auctionSale(Auction a, UUID buyer, String buyerName, BigDecimal price, BigDecimal tax, boolean bidMode,
                            BigDecimal buyerBalance, String buyerNo, String sellerNo, long now) {
        Currency c = plugin.currencies().get(a.currency);
        String pct = percent(plugin.market().auctionTax(a.currency));
        String bidsText = t("values.bids", Vars.of("count", a.bids.size(), "people", a.candidates().size()));

        Receipt b = base(bidMode ? "auction-won" : "auction-buy", buyerNo, now);
        field(b, "id", buyerNo);
        field(b, bidMode ? "won-at" : "time", time(now));
        field(b, "type", t(bidMode ? "trade-types.auction-won" : "trade-types.auction-buy"));
        field(b, "auction-id", "#A-" + a.id);
        field(b, bidMode ? "winner" : "buyer", buyerName);
        field(b, "seller", a.sellerName);
        field(b, "currency", c.name());
        field(b, bidMode ? "bids" : "count", bidMode ? bidsText : countText(a.items));
        lines(b, a.items);
        if (bidMode) {
            b.totals.add(new Receipt.Total(t("totals.start-price"), c.format(a.price), null));
            b.totals.add(new Receipt.Total(t("totals.win-price"), c.format(price), null));
        } else {
            b.totals.add(new Receipt.Total(t("totals.price"), c.format(price), null));
        }
        b.totals.add(new Receipt.Total(t("totals.tax", Vars.of("percent", pct)), c.format(tax), null));
        if (buyerBalance != null) {
            b.totals.add(new Receipt.Total(t("totals.balance-after"), c.format(c.round(buyerBalance)), null));
        }
        b.totals.add(new Receipt.Total(t(bidMode ? "totals.charged" : "totals.paid"), c.format(price.add(tax)), "total"));
        b.notes.add(t(bidMode ? "notes.paid-on-win" : "notes.paid-from", Vars.of("currency", c.name())));
        b.notes.add(t("notes.claim-box"));
        b.summaryType = t(bidMode ? "trade-types.auction-won" : "trade-types.auction-buy");
        b.summaryAuction = "#A-" + a.id;
        b.summaryAmount = c.format(price.add(tax));
        flow(b, c, price.add(tax));
        Map<String, String> bv = Vars.of("player", buyerName, "seller", a.sellerName, "id", a.id, "price", c.format(price.add(tax)),
                "items", plugin.auctions().summary(a), "receipt", buyerNo);
        plugin.notifier().personal(buyer, buyerName, bidMode ? PlayerData.N_WON : PlayerData.N_PURCHASE,
                bidMode ? "discord.dm.won" : "discord.dm.auction-buy", bv, b);

        Receipt s = base("auction-sold", sellerNo, now);
        field(s, "id", sellerNo);
        field(s, "time", time(now));
        field(s, "type", t(bidMode ? "trade-types.sold-bid" : "trade-types.sold-fixed"));
        field(s, "auction-id", "#A-" + a.id);
        field(s, "seller", a.sellerName);
        field(s, "buyer", buyerName);
        field(s, "currency", c.name());
        field(s, "listed-at", time(a.created));
        lines(s, a.items);
        s.totals.add(new Receipt.Total(t("totals.price"), c.format(price), null));
        s.totals.add(new Receipt.Total(t("totals.buyer-tax", Vars.of("percent", pct)), c.format(tax), null));
        s.totals.add(new Receipt.Total(t("totals.received"), c.format(price), "total"));
        s.notes.add(t("notes.deposited", Vars.of("currency", c.name())));
        s.summaryType = t(bidMode ? "trade-types.sold-bid" : "trade-types.sold-fixed");
        s.summaryAuction = "#A-" + a.id;
        s.summaryAmount = c.format(price);
        flow(s, c, price.negate());
        Map<String, String> sv = Vars.of("player", a.sellerName, "buyer", buyerName, "id", a.id, "price", c.format(price),
                "items", plugin.auctions().summary(a), "receipt", sellerNo);
        plugin.notifier().personal(a.seller, a.sellerName, PlayerData.N_SOLD, "discord.dm.sold", sv, s);

        plugin.notifier().admin("discord.admin.sale", Vars.of("buyer", buyerName, "seller", a.sellerName, "id", a.id,
                "mode", mode(a), "price", c.format(price), "tax", c.format(tax), "items", plugin.auctions().summary(a)));
    }

    public void penalty(Auction a, Auction.Bid cand, BigDecimal fine, BigDecimal taken, BigDecimal debt, long until, long now) {
        Currency c = plugin.currencies().get(a.currency);
        String no = plugin.meta().nextReceipt(plugin.config().zone);
        Receipt r = base("penalty", no, now);
        r.itemsTitle = t("sections.auction-items");
        field(r, "id", no);
        field(r, "time", time(now));
        field(r, "type", t("trade-types.penalty"));
        field(r, "auction-id", "#A-" + a.id);
        field(r, "winner", cand.name());
        field(r, "seller", a.sellerName);
        field(r, "currency", c.name());
        field(r, "ban-until", until > 0 ? time(until) : t("values.none"));
        lines(r, a.items);
        r.totals.add(new Receipt.Total(t("totals.win-price"), c.format(cand.amount()), null));
        r.totals.add(new Receipt.Total(t("totals.fine", Vars.of("percent", percent(plugin.market().penaltyFinePercent))), c.format(fine), null));
        r.totals.add(new Receipt.Total(t("totals.fine-paid"), c.format(taken), null));
        r.totals.add(new Receipt.Total(t("totals.debt"), c.format(debt), "total"));
        if (until > 0) {
            r.notes.add(t("notes.penalty-ban", Vars.of("until", time(until))));
        }
        if (debt.signum() > 0) {
            r.notes.add(t("notes.penalty-debt"));
        }
        r.notes.add(t("notes.penalty-next"));
        r.summaryType = t("trade-types.penalty");
        r.summaryAuction = "#A-" + a.id;
        r.summaryAmount = c.format(taken);
        flow(r, c, taken);
        Map<String, String> vars = Vars.of("player", cand.name(), "id", a.id, "price", c.format(cand.amount()), "fine", c.format(fine),
                "debt", c.format(debt), "until", until > 0 ? time(until) : t("values.none"), "receipt", no);
        plugin.notifier().personal(cand.bidder(), cand.name(), 0, "discord.dm.penalty", vars, r);
        plugin.notifier().admin("discord.admin.penalty", vars);
    }

    private void flow(Receipt r, Currency c, BigDecimal spent) {
        r.flowCurrency = c.name();
        r.flowValue = spent.doubleValue();
        r.flowAmount = c.format(spent.abs());
    }

    private String duration(long ms) {
        return TimeUtil.formatDuration(ms, plugin.messages().plain("time.day", Map.of()), plugin.messages().plain("time.hour", Map.of()),
                plugin.messages().plain("time.minute", Map.of()), plugin.messages().plain("time.second", Map.of()));
    }
}
