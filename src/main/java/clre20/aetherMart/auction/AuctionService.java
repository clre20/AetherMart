package clre20.aetherMart.auction;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.data.TradeRecord;
import clre20.aetherMart.data.TradeStore;
import clre20.aetherMart.player.ClaimEntry;
import clre20.aetherMart.util.ItemCodec;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Texts;
import clre20.aetherMart.util.Vars;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 拍賣：上架、一口價購買、競標出價、落槌、結算、懲罰、下架。
 * 狀態只在主執行緒修改；檢查狀態 → 扣款 → 改狀態在同一個 tick 內完成，同一筆拍賣不會被兩人同時買走。
 */
public final class AuctionService {

    public enum Sort { NEWEST, ENDING, PRICE_ASC, PRICE_DESC }

    /** 建立拍賣的草稿（介面用）。 */
    public static final class Draft {
        public final ItemStack[] items = new ItemStack[27];
        public Auction.Mode mode = Auction.Mode.FIXED;
        public String currency;
        public BigDecimal price;
        public long duration;
    }

    private record Event(long time, long id) {
    }

    private final AetherMart plugin;
    private final Map<Long, Auction> active = new LinkedHashMap<>();
    private final PriorityQueue<Event> events = new PriorityQueue<>(Comparator.comparingLong(Event::time).thenComparingLong(Event::id));
    private final Map<Long, CompletableFuture<?>> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Queue<Runnable> deferred = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private BukkitTask ticker;
    private int version;

    public AuctionService(AetherMart plugin) {
        this.plugin = plugin;
    }

    // ---------------- 載入與排程 ----------------

    public void load() throws Exception {
        List<Auction> loaded = plugin.db().call(this::readAll, 60);
        long now = System.currentTimeMillis();
        active.clear();
        events.clear();
        List<Auction> resettle = new ArrayList<>();
        for (Auction a : loaded) {
            active.put(a.id, a);
            if (a.phase == Auction.Phase.KNOCKING) {
                a.knock = 0;
                a.nextKnockAt = now + plugin.market().knockIntervalMs;
                persistPhase(a);
            } else if (a.phase == Auction.Phase.SETTLING) {
                resettle.add(a);
                continue;
            }
            schedule(a);
        }
        version++;
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        // 重啟前正在結算的拍賣：等伺服器啟動完成（5 秒後）再繼續，且跳過已經因這筆拍賣受罰的出價者
        if (!resettle.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> resumeSettlements(resettle), 100L);
        }
    }

    private void resumeSettlements(List<Auction> list) {
        List<Long> ids = new ArrayList<>();
        for (Auction a : list) {
            ids.add(a.id);
        }
        plugin.db().submit(conn -> {
            Map<Long, Set<UUID>> punished = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT actor FROM trades WHERE type = ? AND auction_id = ?")) {
                for (long id : ids) {
                    ps.setString(1, TradeRecord.Type.PENALTY.name());
                    ps.setLong(2, id);
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String actor = rs.getString(1);
                            if (actor != null) {
                                try {
                                    punished.computeIfAbsent(id, k -> new HashSet<>()).add(UUID.fromString(actor));
                                } catch (IllegalArgumentException ignored) {
                                    // 壞資料就略過
                                }
                            }
                        }
                    }
                }
            }
            return punished;
        }).whenComplete((punished, err) -> runMain(() -> {
            for (Auction a : list) {
                if (active.get(a.id) != a || a.phase != Auction.Phase.SETTLING) {
                    continue;
                }
                Set<UUID> skip = punished == null ? Set.of() : punished.getOrDefault(a.id, Set.of());
                List<Auction.Bid> cands = new ArrayList<>();
                for (Auction.Bid b : a.candidates()) {
                    if (!skip.contains(b.bidder())) {
                        cands.add(b);
                    }
                }
                plugin.activity().log("AUCTION", "resume settlement #" + a.id + " after restart (skip " + skip.size() + " punished bidder(s))");
                version++;
                tryCandidate(a, cands, 0);
            }
        }));
    }

    private List<Auction> readAll(Connection c) throws SQLException {
        Map<Long, Auction> map = new LinkedHashMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM auctions ORDER BY id")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                try {
                    Auction a = new Auction(id, UUID.fromString(rs.getString("seller")), rs.getString("seller_name"),
                            Auction.Mode.valueOf(rs.getString("mode")), rs.getString("currency"), new BigDecimal(rs.getString("price")),
                            ItemCodec.decodeList(rs.getBytes("items")), rs.getLong("created"), rs.getLong("ends"), rs.getString("receipt"));
                    a.phase = Auction.Phase.valueOf(rs.getString("phase"));
                    a.knock = rs.getInt("knock");
                    a.nextKnockAt = rs.getLong("next_knock");
                    map.put(id, a);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Skipped unreadable auction #" + id + ": " + e.getMessage());
                }
            }
        }
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT auction_id, bidder, bidder_name, amount, time FROM bids ORDER BY rowid")) {
            while (rs.next()) {
                Auction a = map.get(rs.getLong(1));
                if (a != null) {
                    a.bids.add(new Auction.Bid(UUID.fromString(rs.getString(2)), rs.getString(3), new BigDecimal(rs.getString(4)), rs.getLong(5)));
                }
            }
        }
        for (Auction a : map.values()) {
            a.searchText = searchText(a);
        }
        return new ArrayList<>(map.values());
    }

    private String searchText(Auction a) {
        StringBuilder sb = new StringBuilder(a.sellerName.toLowerCase(Locale.ROOT));
        for (ItemStack i : a.items) {
            sb.append(' ').append(plugin.names().searchText(i));
        }
        return sb.toString();
    }

    public void refreshSearchText() {
        for (Auction a : active.values()) {
            a.searchText = searchText(a);
        }
    }

    private void schedule(Auction a) {
        events.add(new Event(a.nextEventAt(), a.id));
    }

    /** 每秒：只看最早到期的項目。 */
    private void tick() {
        long now = System.currentTimeMillis();
        int guard = 0;
        while (!events.isEmpty() && events.peek().time() <= now && guard++ < 200) {
            Event e = events.poll();
            Auction a = active.get(e.id());
            if (a == null || a.phase == Auction.Phase.SETTLING || a.nextEventAt() != e.time()) {
                continue;
            }
            if (a.phase == Auction.Phase.ACTIVE) {
                if (a.mode == Auction.Mode.FIXED || a.bids.isEmpty()) {
                    expire(a, false);
                } else {
                    startKnocking(a, now);
                }
            } else {
                knock(a, now);
            }
        }
    }

    private void startKnocking(Auction a, long now) {
        a.phase = Auction.Phase.KNOCKING;
        a.knock = 0;
        a.nextKnockAt = now + plugin.market().knockIntervalMs;
        persistPhase(a);
        schedule(a);
        version++;
        announce(a, "auction.knock-start", Vars.of("id", a.id, "minutes", plugin.market().knockIntervalMs / 60000));
        plugin.activity().log("AUCTION", "#" + a.id + " knocking started, highest " + a.currentPrice().toPlainString());
    }

    private void knock(Auction a, long now) {
        a.knock++;
        int count = plugin.market().knockCount;
        announce(a, "auction.knock", Vars.of("id", a.id, "n", a.knock, "count", count,
                "price", fmt(a.currency, a.currentPrice())));
        plugin.activity().log("AUCTION", "#" + a.id + " knock " + a.knock + "/" + count);
        if (a.knock >= count) {
            settle(a);
        } else {
            a.nextKnockAt = now + plugin.market().knockIntervalMs;
            persistPhase(a);
            schedule(a);
        }
        version++;
    }

    /** 通知在線上的賣家與出價者。 */
    private void announce(Auction a, String key, Map<String, String> vars) {
        Set<UUID> to = new HashSet<>();
        to.add(a.seller);
        for (Auction.Bid b : a.bids) {
            to.add(b.bidder());
        }
        for (UUID u : to) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                plugin.messages().send(p, key, vars);
            }
        }
    }

    // ---------------- 查詢 ----------------

    public Auction get(long id) {
        return active.get(id);
    }

    public Collection<Auction> all() {
        return active.values();
    }

    public int version() {
        return version;
    }

    public List<Auction> browse(Sort sort, Auction.Mode mode, String currency, String search) {
        List<Auction> out = new ArrayList<>();
        String q = search == null ? null : search.toLowerCase(Locale.ROOT);
        for (Auction a : active.values()) {
            if (a.phase == Auction.Phase.SETTLING) {
                continue;
            }
            if (mode != null && a.mode != mode) {
                continue;
            }
            if (currency != null && !a.currency.equals(currency)) {
                continue;
            }
            if (q != null && !q.isEmpty() && !a.searchText.contains(q)) {
                continue;
            }
            out.add(a);
        }
        Comparator<Auction> cmp = switch (sort) {
            case NEWEST -> Comparator.comparingLong((Auction a) -> a.created).reversed();
            case ENDING -> Comparator.comparingLong(Auction::nextEventAt);
            case PRICE_ASC -> Comparator.comparing(Auction::currentPrice);
            case PRICE_DESC -> Comparator.comparing(Auction::currentPrice).reversed();
        };
        out.sort(cmp.thenComparingLong(a -> a.id));
        return out;
    }

    public List<Auction> listedBy(UUID seller) {
        List<Auction> out = new ArrayList<>();
        for (Auction a : active.values()) {
            if (a.seller.equals(seller)) {
                out.add(a);
            }
        }
        return out;
    }

    public List<Auction> biddingBy(UUID bidder) {
        List<Auction> out = new ArrayList<>();
        for (Auction a : active.values()) {
            if (a.hasBidFrom(bidder)) {
                out.add(a);
            }
        }
        return out;
    }

    public boolean paused() {
        return Boolean.parseBoolean(plugin.meta().get("auction.paused", "false"));
    }

    public void setPaused(boolean paused, String by) {
        plugin.meta().set("auction.paused", String.valueOf(paused));
        plugin.activity().log("ADMIN", by + (paused ? " paused" : " resumed") + " the auction house");
        plugin.notifier().admin("discord.admin." + (paused ? "paused" : "resumed"), Vars.of("admin", by));
    }

    public int listingLimit(Player p) {
        int limit = plugin.market().defaultListingLimit;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (info.getValue() && perm.startsWith("aethermart.limit.")) {
                try {
                    limit = Math.max(limit, Math.min(1000, Integer.parseInt(perm.substring("aethermart.limit.".length()))));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return limit;
    }

    public BigDecimal minNextBid(Auction a) {
        Currency c = plugin.currencies().get(a.currency);
        Auction.Bid h = a.highest();
        if (h == null) {
            return a.price;
        }
        BigDecimal next = h.amount().multiply(BigDecimal.valueOf(100).add(plugin.market().minRaisePercent))
                .divide(BigDecimal.valueOf(100));
        next = c == null ? next : c.ceil(next);
        if (next.compareTo(h.amount()) <= 0) {
            BigDecimal step = c == null ? BigDecimal.ONE : BigDecimal.ONE.movePointLeft(c.decimals());
            next = h.amount().add(step);
        }
        return next;
    }

    public BigDecimal tax(Auction a, BigDecimal amount) {
        Currency c = plugin.currencies().get(a.currency);
        return c == null ? BigDecimal.ZERO : c.tax(amount, plugin.market().auctionTax(a.currency));
    }

    private String fmt(String currency, BigDecimal v) {
        Currency c = plugin.currencies().get(currency);
        return c == null ? v.toPlainString() + " " + currency : c.format(v);
    }

    // ---------------- 上架 ----------------

    /** 送出按鈕用：目前不能上架的原因（不送訊息）；空的代表可以上架。 */
    public List<String> createBlockers(Player p, Draft d, List<ItemStack> items) {
        List<String> out = new ArrayList<>();
        var g = plugin.guard();
        if (!p.hasPermission("aethermart.auction.sell")) {
            out.add(g.reason("reason.no-permission", Map.of()));
            return out;
        }
        if (paused()) {
            out.add(g.reason("reason.paused", Map.of()));
        }
        if (items.isEmpty()) {
            out.add(g.reason("reason.no-items", Map.of()));
        }
        for (ItemStack i : items) {
            if (plugin.banned().isBanned(i)) {
                out.add(g.reason("reason.banned-item", Vars.of("item", plugin.names().name(i))));
                break;
            }
        }
        Currency c = plugin.currencies().get(d.currency);
        if (c == null || !plugin.market().auctionCurrencies.contains(d.currency)) {
            out.add(g.reason("reason.bad-currency", Map.of()));
        } else if (!c.available()) {
            out.add(g.reason("reason.currency-unavailable", Vars.of("currency", c.name())));
        } else if (d.price == null) {
            out.add(g.reason("reason.no-price", Map.of()));
        } else {
            BigDecimal min = plugin.market().auctionMin(d.currency);
            if (d.price.compareTo(min) < 0 || d.price.compareTo(plugin.config().maxAmount) > 0) {
                out.add(g.reason("reason.price-range", Vars.of("min", c.format(min), "max", c.format(plugin.config().maxAmount))));
            }
        }
        long duration = d.duration <= 0 ? plugin.market().durationDefault : d.duration;
        if (duration < plugin.market().durationMin || duration > plugin.market().durationMax) {
            out.add(g.reason("reason.duration-range", Vars.of("min", durationText(plugin.market().durationMin),
                    "max", durationText(plugin.market().durationMax))));
        }
        if (listedBy(p.getUniqueId()).size() >= listingLimit(p)) {
            out.add(g.reason("reason.listing-limit", Vars.of("limit", listingLimit(p))));
        }
        out.addAll(g.reasons(p, false));
        return out;
    }

    private String durationText(long ms) {
        var m = plugin.messages();
        return TimeUtil.formatShort(ms, m.plain("time.day", Map.of()), m.plain("time.hour", Map.of()), m.plain("time.minute", Map.of()));
    }

    /** 驗證並建立拍賣；成功時清空草稿物品並回傳 true。 */
    public boolean create(Player p, Draft d) {
        return create(p, d, null);
    }

    /** 建立拍賣；成功時把結果畫面用的明細放進 out（id、mode、price、ends）。 */
    public boolean create(Player p, Draft d, Map<String, String> out) {
        if (!p.hasPermission("aethermart.auction.sell")) {
            plugin.messages().send(p, "common.no-permission");
            return false;
        }
        if (paused()) {
            plugin.messages().send(p, "auction.paused");
            return false;
        }
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack i : d.items) {
            if (i != null && !i.getType().isAir()) {
                items.add(i);
            }
        }
        if (items.isEmpty()) {
            plugin.messages().send(p, "auction.create.empty");
            return false;
        }
        for (ItemStack i : items) {
            if (plugin.banned().isBanned(i)) {
                plugin.messages().send(p, "auction.create.banned", Vars.of("item", plugin.names().name(i)));
                return false;
            }
        }
        Currency c = plugin.currencies().get(d.currency);
        if (c == null || !plugin.market().auctionCurrencies.contains(d.currency)) {
            plugin.messages().send(p, "auction.create.bad-currency");
            return false;
        }
        if (!c.available()) {
            plugin.messages().send(p, "currency.unavailable", Vars.of("currency", c.name()));
            return false;
        }
        if (d.price == null) {
            plugin.messages().send(p, "auction.create.no-price");
            return false;
        }
        BigDecimal min = plugin.market().auctionMin(d.currency);
        if (d.price.compareTo(min) < 0 || d.price.compareTo(plugin.config().maxAmount) > 0) {
            plugin.messages().send(p, "auction.create.price-range", Vars.of("min", c.format(min), "max", c.format(plugin.config().maxAmount)));
            return false;
        }
        long duration = d.duration <= 0 ? plugin.market().durationDefault : d.duration;
        if (duration < plugin.market().durationMin || duration > plugin.market().durationMax) {
            plugin.messages().send(p, "auction.create.duration-range");
            return false;
        }
        if (listedBy(p.getUniqueId()).size() >= listingLimit(p)) {
            plugin.messages().send(p, "auction.create.limit", Vars.of("limit", listingLimit(p)));
            return false;
        }
        if (!plugin.guard().check(p, false)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long maxId = 0;
        for (Long id : active.keySet()) {
            maxId = Math.max(maxId, id);
        }
        long id = plugin.meta().nextAuctionId(maxId);
        String receipt = plugin.meta().nextReceipt(plugin.config().zone);
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack i : items) {
            copy.add(i.clone());
        }
        Auction a = new Auction(id, p.getUniqueId(), p.getName(), d.mode, d.currency, c.round(d.price), copy, now, now + duration, receipt);
        a.searchText = searchText(a);
        java.util.Arrays.fill(d.items, null);
        active.put(id, a);
        schedule(a);
        version++;
        byte[] blob = ItemCodec.encodeList(copy);
        plugin.db().execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO auctions(id,seller,seller_name,mode,currency,price,items,created,ends,phase,knock,next_knock,receipt) VALUES(?,?,?,?,?,?,?,?,?,?,0,0,?)")) {
                ps.setLong(1, a.id);
                ps.setString(2, a.seller.toString());
                ps.setString(3, a.sellerName);
                ps.setString(4, a.mode.name());
                ps.setString(5, a.currency);
                ps.setString(6, a.price.toPlainString());
                ps.setBytes(7, blob);
                ps.setLong(8, a.created);
                ps.setLong(9, a.endsAt);
                ps.setString(10, a.phase.name());
                ps.setString(11, receipt);
                ps.executeUpdate();
            }
        });
        String summary = summary(a);
        plugin.trades().insert(new TradeRecord(0, now, TradeRecord.Type.LIST, p.getUniqueId(), p.getName(), null, null,
                a.currency, a.price.toPlainString(), "0", summary, a.id, receipt));
        plugin.activity().log("TRADE", "LIST #" + a.id + " " + p.getName() + " (" + p.getUniqueId() + ") " + a.mode + " "
                + c.format(a.price) + " ends " + TimeUtil.format(a.endsAt, plugin.config().zone, TimeUtil.DATE_TIME) + " items: " + summary);
        plugin.messages().send(p, "auction.create.done", Vars.of("id", a.id, "price", c.format(a.price)));
        plugin.sounds().success(p);
        plugin.receipts().auctionListed(a);
        if (out != null) {
            out.put("id", String.valueOf(a.id));
            out.put("mode", plugin.messages().plain(a.mode == Auction.Mode.FIXED ? "auction.mode.fixed" : "auction.mode.bid", Map.of()));
            out.put("price", c.format(a.price));
            out.put("ends", TimeUtil.format(a.endsAt, plugin.config().zone, TimeUtil.DATE_TIME_SHORT));
        }
        return true;
    }

    public String summary(Auction a) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack i : a.items) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(plugin.names().name(i)).append(" x").append(i.getAmount());
            if (sb.length() > 200) {
                sb.append("…");
                break;
            }
        }
        return sb.toString();
    }

    // ---------------- 一口價購買 ----------------

    public boolean buy(Player p, Auction a) {
        return buy(p, a, null);
    }

    /** 一口價購買；成功時把明細放進 out（id、seller、paid、tax、balance）。 */
    public boolean buy(Player p, Auction a, Map<String, String> out) {
        if (!p.hasPermission("aethermart.use")) {
            plugin.messages().send(p, "common.no-permission");
            return false;
        }
        if (active.get(a.id) != a || a.phase != Auction.Phase.ACTIVE || a.mode != Auction.Mode.FIXED) {
            plugin.messages().send(p, "auction.gone");
            return false;
        }
        if (paused()) {
            plugin.messages().send(p, "auction.paused");
            return false;
        }
        if (a.seller.equals(p.getUniqueId())) {
            plugin.messages().send(p, "auction.own");
            return false;
        }
        Currency c = plugin.currencies().get(a.currency);
        if (c == null || !c.available()) {
            plugin.messages().send(p, "currency.unavailable", Vars.of("currency", c == null ? a.currency : c.name()));
            return false;
        }
        if (!plugin.guard().check(p, true)) {
            return false;
        }
        BigDecimal tax = tax(a, a.price);
        BigDecimal total = a.price.add(tax);
        if (!c.provider().withdraw(p, total)) {
            plugin.messages().send(p, "common.not-enough", Vars.of("amount", c.format(total)));
            return false;
        }
        BigDecimal balance = c.provider().balance(p);
        completeSale(a, p.getUniqueId(), p.getName(), a.price, tax, false, balance);
        plugin.sounds().success(p);
        if (out != null) {
            out.put("id", String.valueOf(a.id));
            out.put("seller", a.sellerName);
            out.put("paid", c.format(total));
            out.put("tax", c.format(tax));
            out.put("balance", c.format(c.round(balance)));
        }
        return true;
    }

    // ---------------- 出價 ----------------

    public boolean bid(Player p, Auction a, BigDecimal amount) {
        return bid(p, a, amount, null);
    }

    /** 出價；成功時把明細放進 out（id、price、need）。 */
    public boolean bid(Player p, Auction a, BigDecimal amount, Map<String, String> out) {
        if (!p.hasPermission("aethermart.use")) {
            plugin.messages().send(p, "common.no-permission");
            return false;
        }
        if (active.get(a.id) != a || a.mode != Auction.Mode.BID || a.phase == Auction.Phase.SETTLING) {
            plugin.messages().send(p, "auction.gone");
            return false;
        }
        if (paused()) {
            plugin.messages().send(p, "auction.paused");
            return false;
        }
        if (a.seller.equals(p.getUniqueId())) {
            plugin.messages().send(p, "auction.own");
            return false;
        }
        Auction.Bid prev = a.highest();
        if (prev != null && prev.bidder().equals(p.getUniqueId())) {
            plugin.messages().send(p, "auction.bid.already-highest");
            return false;
        }
        Currency c = plugin.currencies().get(a.currency);
        if (c == null || !c.available()) {
            plugin.messages().send(p, "currency.unavailable", Vars.of("currency", c == null ? a.currency : c.name()));
            return false;
        }
        BigDecimal min = minNextBid(a);
        if (amount.compareTo(min) < 0) {
            plugin.messages().send(p, "auction.bid.too-low", Vars.of("min", c.format(min)));
            return false;
        }
        if (!plugin.guard().check(p, true)) {
            return false;
        }
        BigDecimal need = amount.add(tax(a, amount));
        if (c.provider().balance(p).compareTo(need) < 0) {
            plugin.messages().send(p, "auction.bid.not-enough", Vars.of("amount", c.format(need)));
            return false;
        }
        long now = System.currentTimeMillis();
        Auction.Bid b = new Auction.Bid(p.getUniqueId(), p.getName(), amount, now);
        a.bids.add(b);
        plugin.db().execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO bids(auction_id,bidder,bidder_name,amount,time) VALUES(?,?,?,?,?)")) {
                ps.setLong(1, a.id);
                ps.setString(2, b.bidder().toString());
                ps.setString(3, b.name());
                ps.setString(4, b.amount().toPlainString());
                ps.setLong(5, b.time());
                ps.executeUpdate();
            }
        });
        if (a.phase == Auction.Phase.KNOCKING) {
            a.knock = 0;
            a.nextKnockAt = now + plugin.market().knockIntervalMs;
            persistPhase(a);
            schedule(a);
            announce(a, "auction.knock-reset", Vars.of("id", a.id, "price", c.format(amount)));
        }
        version++;
        plugin.trades().insert(new TradeRecord(0, now, TradeRecord.Type.BID, p.getUniqueId(), p.getName(), a.seller, a.sellerName,
                a.currency, amount.toPlainString(), "0", summary(a), a.id, ""));
        plugin.activity().log("TRADE", "BID #" + a.id + " " + p.getName() + " (" + p.getUniqueId() + ") " + c.format(amount));
        plugin.messages().send(p, "auction.bid.done", Vars.of("id", a.id, "price", c.format(amount)));
        plugin.sounds().success(p);
        if (out != null) {
            out.put("id", String.valueOf(a.id));
            out.put("price", c.format(amount));
            out.put("need", c.format(need));
        }
        if (prev != null && !prev.bidder().equals(p.getUniqueId())) {
            Player old = Bukkit.getPlayer(prev.bidder());
            if (old != null) {
                plugin.messages().send(old, "auction.bid.outbid", Vars.of("id", a.id, "price", c.format(amount)));
            }
            plugin.receipts().outbid(a, prev, amount, minNextBid(a));
        }
        return true;
    }

    // ---------------- 下架 ----------------

    public boolean cancel(Player p, Auction a) {
        if (active.get(a.id) != a || !a.seller.equals(p.getUniqueId())) {
            plugin.messages().send(p, "auction.gone");
            return false;
        }
        if (a.phase != Auction.Phase.ACTIVE || (a.mode == Auction.Mode.BID && !a.bids.isEmpty())) {
            plugin.messages().send(p, "auction.cancel.has-bids");
            return false;
        }
        endWithReturn(a, TradeRecord.Type.CANCEL, ClaimEntry.Source.CANCELLED, p.getName());
        plugin.messages().send(p, "auction.cancel.done", Vars.of("id", a.id));
        return true;
    }

    public boolean forceRemove(String admin, Auction a) {
        if (active.get(a.id) != a || a.phase == Auction.Phase.SETTLING) {
            return false;
        }
        endWithReturn(a, TradeRecord.Type.FORCE_REMOVE, ClaimEntry.Source.REMOVED, admin);
        plugin.activity().log("ADMIN", admin + " force removed auction #" + a.id + " of " + a.sellerName);
        plugin.notifier().admin("discord.admin.force-removed", Vars.of("admin", admin, "id", a.id, "seller", a.sellerName, "items", summary(a)));
        return true;
    }

    private void expire(Auction a, boolean allFailed) {
        endWithReturn(a, TradeRecord.Type.EXPIRE, ClaimEntry.Source.EXPIRED, allFailed ? "unpaid" : "time");
        Player s = Bukkit.getPlayer(a.seller);
        if (s != null) {
            plugin.messages().send(s, allFailed ? "auction.expired-unpaid" : "auction.expired", Vars.of("id", a.id));
        }
    }

    private void endWithReturn(Auction a, TradeRecord.Type type, ClaimEntry.Source source, String by) {
        active.remove(a.id);
        settleRetries.remove(a.id);
        version++;
        long now = System.currentTimeMillis();
        deleteAuction(a.id, null);
        plugin.claims().add(ClaimEntry.items(a.seller, new ArrayList<>(a.items), source));
        String receipt = plugin.meta().nextReceipt(plugin.config().zone);
        plugin.trades().insert(new TradeRecord(0, now, type, a.seller, a.sellerName, null, by, a.currency,
                a.currentPrice().toPlainString(), "0", summary(a), a.id, receipt));
        plugin.activity().log("TRADE", type + " #" + a.id + " seller " + a.sellerName + " by " + by + " items: " + summary(a));
        if (type == TradeRecord.Type.EXPIRE) {
            plugin.receipts().auctionExpired(a, receipt, now);
        } else {
            plugin.receipts().auctionRemoved(a, type == TradeRecord.Type.FORCE_REMOVE, by, receipt, now);
        }
    }

    // ---------------- 結算 ----------------

    /** 結算時經濟插件出錯的重試次數（每筆拍賣）；成功或下架後移除。 */
    private final Map<Long, Integer> settleRetries = new HashMap<>();

    private void settle(Auction a) {
        a.phase = Auction.Phase.SETTLING;
        persistPhase(a);
        version++;
        tryCandidate(a, a.candidates(), 0);
    }

    private void tryCandidate(Auction a, List<Auction.Bid> list, int index) {
        if (active.get(a.id) != a) {
            return;
        }
        if (index >= list.size()) {
            expire(a, !list.isEmpty());
            return;
        }
        Auction.Bid cand = list.get(index);
        Currency c = plugin.currencies().get(a.currency);
        if (c == null || !c.available()) {
            // 幣別暫時不能用：延後一分鐘再試
            a.phase = Auction.Phase.KNOCKING;
            a.knock = plugin.market().knockCount - 1;
            a.nextKnockAt = System.currentTimeMillis() + 60_000L;
            persistPhase(a);
            schedule(a);
            plugin.activity().log("AUCTION", "#" + a.id + " settlement delayed: currency unavailable");
            return;
        }
        BigDecimal tax = tax(a, cand.amount());
        BigDecimal total = cand.amount().add(tax);
        // 線上、離線都走同一條路：經濟插件出錯（ProviderException 等）時延後重試，絕不當成付不出錢
        CompletableFuture<Boolean> f;
        try {
            f = c.provider().withdraw(cand.bidder(), total);
        } catch (RuntimeException ex) {
            f = CompletableFuture.failedFuture(ex);
        }
        pending.put(a.id, f);
        f.whenComplete((ok, err) -> runMain(() -> {
            if (pending.remove(a.id) == null) {
                return;
            }
            if (err != null) {
                retrySettlement(a, list, index, err);
                return;
            }
            settleRetries.remove(a.id);
            boolean paid = Boolean.TRUE.equals(ok);
            Player online = Bukkit.getPlayer(cand.bidder());
            onCharge(a, list, index, cand, tax, paid, paid && online != null ? c.provider().balance(online) : null);
        }));
    }

    /** 結算時經濟插件出錯：5 分鐘後從同一位候選人重試；第一次與之後每小時在後台提示一次。 */
    private void retrySettlement(Auction a, List<Auction.Bid> list, int index, Throwable err) {
        int n = settleRetries.merge(a.id, 1, Integer::sum);
        Throwable root = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
        plugin.activity().log("AUCTION", "#" + a.id + " settlement error (try " + n + "): " + root);
        if (n == 1 || n % 12 == 0) {
            for (String line : plugin.messages().list("startup.settle-retry")) {
                plugin.getLogger().warning(Texts.plain(line, Vars.of("id", a.id, "error", String.valueOf(root.getMessage()), "tries", n)));
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (active.get(a.id) == a && a.phase == Auction.Phase.SETTLING) {
                tryCandidate(a, list, index);
            }
        }, 5 * 60 * 20L);
    }

    private void onCharge(Auction a, List<Auction.Bid> list, int index, Auction.Bid cand, BigDecimal tax, boolean ok, BigDecimal balance) {
        if (active.get(a.id) != a) {
            return;
        }
        if (ok) {
            completeSale(a, cand.bidder(), cand.name(), cand.amount(), tax, true, balance);
            return;
        }
        punish(a, cand);
        tryCandidate(a, list, index + 1);
    }

    /** 成交：物品給買家（領取箱）、錢給賣家、紀錄與通知。 */
    private void completeSale(Auction a, UUID buyer, String buyerName, BigDecimal price, BigDecimal tax, boolean bidMode, BigDecimal buyerBalance) {
        active.remove(a.id);
        settleRetries.remove(a.id);
        version++;
        long now = System.currentTimeMillis();
        Currency c = plugin.currencies().get(a.currency);
        String buyerReceipt = plugin.meta().nextReceipt(plugin.config().zone);
        String sellerReceipt = plugin.meta().nextReceipt(plugin.config().zone);
        TradeRecord rec = new TradeRecord(0, now, bidMode ? TradeRecord.Type.SALE_BID : TradeRecord.Type.SALE, buyer, buyerName,
                a.seller, a.sellerName, a.currency, price.toPlainString(), tax.toPlainString(), summary(a), a.id, buyerReceipt);
        deleteAuction(a.id, conn -> TradeStore.insert(conn, rec));
        plugin.claims().add(ClaimEntry.items(buyer, new ArrayList<>(a.items), bidMode ? ClaimEntry.Source.WON : ClaimEntry.Source.BOUGHT));
        payout(a.seller, c, price);
        plugin.activity().log("TRADE", (bidMode ? "SALE_BID" : "SALE") + " #" + a.id + " buyer " + buyerName + " (" + buyer + ") seller "
                + a.sellerName + " (" + a.seller + ") price " + c.format(price) + " tax " + c.format(tax) + " items: " + summary(a));
        Player b = Bukkit.getPlayer(buyer);
        if (b != null) {
            plugin.messages().send(b, bidMode ? "auction.won" : "auction.bought", Vars.of("id", a.id, "price", c.format(price.add(tax))));
        }
        Player s = Bukkit.getPlayer(a.seller);
        if (s != null) {
            plugin.messages().send(s, "auction.sold", Vars.of("id", a.id, "buyer", buyerName, "price", c.format(price)));
        }
        plugin.receipts().auctionSale(a, buyer, buyerName, price, tax, bidMode, buyerBalance, buyerReceipt, sellerReceipt, now);
    }

    /** 付錢給賣家；失敗就放進領取箱。 */
    private void payout(UUID seller, Currency c, BigDecimal amount) {
        Player s = Bukkit.getPlayer(seller);
        if (s != null) {
            if (!c.provider().deposit(s, amount)) {
                plugin.claims().add(ClaimEntry.money(seller, c.id(), amount, ClaimEntry.Source.INCOME));
            }
            return;
        }
        c.provider().deposit(seller, amount).whenComplete((ok, err) -> {
            if (err != null || !Boolean.TRUE.equals(ok)) {
                runMain(() -> plugin.claims().add(ClaimEntry.money(seller, c.id(), amount, ClaimEntry.Source.INCOME)));
            }
        });
    }

    /** 得標者付不出錢：禁止買賣、罰金（不夠的記欠款）、通知。 */
    private void punish(Auction a, Auction.Bid cand) {
        Currency c = plugin.currencies().get(a.currency);
        long now = System.currentTimeMillis();
        int days = plugin.market().penaltyBanDays;
        long until = days > 0 ? now + TimeUnit.DAYS.toMillis(days) : 0;
        String reason = "auction #" + a.id;
        if (until > 0) {
            plugin.players().ban(cand.bidder(), cand.name(), until, reason);
        }
        BigDecimal fine = c.round(cand.amount().multiply(plugin.market().penaltyFinePercent).divide(BigDecimal.valueOf(100)));
        Consumer<BigDecimal> afterFine = taken -> {
            BigDecimal debt = fine.subtract(taken).max(BigDecimal.ZERO);
            if (debt.signum() > 0) {
                plugin.players().addDebt(cand.bidder(), cand.name(), c.id(), debt);
            }
            plugin.trades().insert(new TradeRecord(0, now, TradeRecord.Type.PENALTY, cand.bidder(), cand.name(), a.seller, a.sellerName,
                    a.currency, fine.toPlainString(), "0", summary(a), a.id, ""));
            plugin.activity().log("PENALTY", cand.name() + " (" + cand.bidder() + ") failed to pay auction #" + a.id + " ("
                    + c.format(cand.amount()) + "), banned until " + (until > 0 ? TimeUtil.format(until, plugin.config().zone, TimeUtil.DATE_TIME) : "-")
                    + ", fine " + c.format(fine) + ", collected " + c.format(taken) + ", debt " + c.format(debt));
            Player p = Bukkit.getPlayer(cand.bidder());
            if (p != null) {
                plugin.messages().send(p, "penalty.applied", Vars.of("id", a.id, "fine", c.format(fine), "debt", c.format(debt),
                        "until", until > 0 ? TimeUtil.format(until, plugin.config().zone, TimeUtil.DATE_TIME_SHORT) : "-"));
            }
            plugin.receipts().penalty(a, cand, fine, taken, debt, until, now);
        };
        if (fine.signum() <= 0) {
            afterFine.accept(BigDecimal.ZERO);
            return;
        }
        Player online = Bukkit.getPlayer(cand.bidder());
        if (online != null) {
            BigDecimal take = c.round(c.provider().balance(online)).min(fine).max(BigDecimal.ZERO);
            boolean ok = take.signum() > 0 && c.provider().withdraw(online, take);
            afterFine.accept(ok ? take : BigDecimal.ZERO);
            return;
        }
        c.provider().withdrawUpTo(cand.bidder(), fine).whenComplete((taken, err) ->
                runMain(() -> afterFine.accept(err == null && taken != null ? c.round(taken) : BigDecimal.ZERO)));
    }

    // ---------------- 資料庫 ----------------

    private void persistPhase(Auction a) {
        String phase = a.phase.name();
        int knock = a.knock;
        long next = a.nextKnockAt;
        long id = a.id;
        plugin.db().execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE auctions SET phase=?, knock=?, next_knock=? WHERE id=?")) {
                ps.setString(1, phase);
                ps.setInt(2, knock);
                ps.setLong(3, next);
                ps.setLong(4, id);
                ps.executeUpdate();
            }
        });
    }

    private void deleteAuction(long id, clre20.aetherMart.data.Database.SqlAction extra) {
        plugin.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM bids WHERE auction_id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM auctions WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            if (extra != null) {
                extra.run(c);
            }
        });
    }

    private void runMain(Runnable r) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, r);
        } else {
            deferred.add(r);
        }
    }

    /** 關服：停止排程，等待進行中的離線扣款與入帳（最多約 10 秒），在主執行緒處理結果後才寫入資料庫。 */
    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            for (CompletableFuture<?> f : new ArrayList<>(pending.values())) {
                try {
                    f.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                }
            }
            Runnable r;
            boolean ran = false;
            while ((r = deferred.poll()) != null) {
                ran = true;
                try {
                    r.run();
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Auction shutdown task failed: " + e);
                }
            }
            if (!ran && pending.isEmpty()) {
                break;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
