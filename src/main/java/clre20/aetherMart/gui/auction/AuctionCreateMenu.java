package clre20.aetherMart.gui.auction;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.auction.Auction;
import clre20.aetherMart.auction.AuctionService;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.currency.CurrencyRegistry;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.player.ClaimEntry;
import clre20.aetherMart.util.Inventories;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 建立拍賣（4 排）：上 3 排放要賣的物品（只接受左鍵拿起、放下），下排是設定。
 * 物品在介面裡時只存在介面中；等聊天輸入時暫存在草稿；關閉、取消、離線、關服一律退回（放不下的進領取箱）。
 */
public final class AuctionCreateMenu extends Menu {

    private static final Set<InventoryAction> ALLOWED = EnumSet.of(InventoryAction.PICKUP_ALL, InventoryAction.PLACE_ALL,
            InventoryAction.PLACE_SOME, InventoryAction.PLACE_ONE, InventoryAction.SWAP_WITH_CURSOR, InventoryAction.NOTHING);

    private final AuctionService.Draft draft;
    private boolean finished;

    public AuctionCreateMenu(AetherMart plugin, Player viewer, AuctionService.Draft draft) {
        super(plugin, viewer);
        if (draft == null) {
            draft = new AuctionService.Draft();
            for (String id : plugin.market().auctionCurrencies) {
                if (plugin.currencies().get(id) != null) {
                    draft.currency = id;
                    break;
                }
            }
            draft.duration = plugin.market().durationDefault;
        }
        this.draft = draft;
    }

    @Override
    public String getMenuId() {
        return "auction_create";
    }

    @Override
    protected String titleKey() {
        return "gui.auction.create.title";
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    protected void clearButtons() {
        for (int i = 27; i < 36; i++) {
            inv().setItem(i, null);
        }
    }

    @Override
    public void open() {
        super.open();
    }

    @Override
    protected void build() {
        // 草稿裡的物品放回介面（只在打開時），之後物品只存在介面中
        for (int i = 0; i < 27; i++) {
            if (draft.items[i] != null) {
                inv().setItem(i, draft.items[i]);
                draft.items[i] = null;
            }
        }
        Currency c = plugin.currencies().get(draft.currency);
        Map<String, String> vars = vars(c);
        set(27, plugin.icons().of(Material.BARRIER, "gui.auction.create.cancel"), e -> viewer.closeInventory());
        set(28, plugin.icons().filler());
        set(29, plugin.icons().of(draft.mode == Auction.Mode.FIXED ? Material.EMERALD : Material.GOLD_INGOT, "gui.auction.create.mode", vars), e -> {
            draft.mode = draft.mode == Auction.Mode.FIXED ? Auction.Mode.BID : Auction.Mode.FIXED;
            refresh();
        });
        set(30, plugin.icons().of(Material.GOLD_NUGGET, "gui.auction.create.currency", vars), e -> {
            List<String> ids = new ArrayList<>();
            for (String id : plugin.market().auctionCurrencies) {
                if (plugin.currencies().get(id) != null) {
                    ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                return;
            }
            int i = ids.indexOf(draft.currency);
            draft.currency = ids.get((i + 1) % ids.size());
            draft.price = null;
            refresh();
        });
        set(31, plugin.icons().of(Material.NAME_TAG, draft.mode == Auction.Mode.FIXED ? "gui.auction.create.price" : "gui.auction.create.start", vars), e -> {
            Currency cur = plugin.currencies().get(draft.currency);
            if (cur == null) {
                return;
            }
            askKeeping("auction.input.price", Vars.of("min", cur.format(plugin.market().auctionMin(cur.id())), "currency", cur.name()), text -> {
                BigDecimal v = CurrencyRegistry.parseAmount(text, cur, plugin.config().maxAmount);
                if (v == null) {
                    plugin.messages().send(viewer, "common.bad-amount", Vars.of("decimals", cur.decimals()));
                } else if (v.compareTo(plugin.market().auctionMin(cur.id())) < 0) {
                    plugin.messages().send(viewer, "auction.create.price-range", Vars.of("min", cur.format(plugin.market().auctionMin(cur.id())),
                            "max", cur.format(plugin.config().maxAmount)));
                } else {
                    draft.price = v;
                }
            });
        });
        set(32, plugin.icons().of(Material.CLOCK, "gui.auction.create.duration", vars), e -> askKeeping("auction.input.duration", Vars.of(
                "min", duration(plugin.market().durationMin), "max", duration(plugin.market().durationMax)), text -> {
            long ms = TimeUtil.parseDurationMillis(text);
            if (ms < plugin.market().durationMin || ms > plugin.market().durationMax) {
                plugin.messages().send(viewer, "auction.create.duration-range-detail", Vars.of("min", duration(plugin.market().durationMin),
                        "max", duration(plugin.market().durationMax)));
            } else {
                draft.duration = ms;
            }
        }));
        set(33, plugin.icons().of(Material.BOOK, "gui.auction.create.info", vars));
        set(34, plugin.icons().filler());
        updateConfirm(vars);
    }

    /** 送出按鈕：能上架才是綠色，否則紅色並列出原因。放入／拿出物品後也會更新。 */
    private void updateConfirm(Map<String, String> vars) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            ItemStack it = inv().getItem(i);
            if (it != null && !it.getType().isAir()) {
                items.add(it);
            } else if (draft.items[i] != null) {
                items.add(draft.items[i]);
            }
        }
        List<String> reasons = plugin.auctions().createBlockers(viewer, draft, items);
        set(35, plugin.icons().confirm(Material.LIME_CONCRETE, "gui.auction.create.confirm", vars, reasons), e -> confirm());
    }

    private boolean updateQueued;

    /** 點擊處理完（物品真的移動後）的下一刻更新送出按鈕；同一刻多次點擊只更新一次。 */
    private void queueConfirmUpdate() {
        if (updateQueued) {
            return;
        }
        updateQueued = true;
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            updateQueued = false;
            if (!finished && !isSuspended() && viewer.getOpenInventory().getTopInventory().getHolder(false) == this) {
                updateConfirm(vars(plugin.currencies().get(draft.currency)));
            }
        });
    }

    private Map<String, String> vars(Currency c) {
        String none = plain("gui.auction.create.unset", Map.of());
        return Vars.of("mode", plain(draft.mode == Auction.Mode.FIXED ? "auction.mode.fixed" : "auction.mode.bid", Map.of()),
                "currency", c == null ? none : c.name(),
                "price", draft.price == null || c == null ? none : c.format(draft.price),
                "duration", duration(draft.duration),
                "tax", c == null ? "0" : plugin.market().auctionTax(c.id()).stripTrailingZeros().toPlainString(),
                "min", c == null ? "" : c.format(plugin.market().auctionMin(c.id())),
                "used", plugin.auctions().listedBy(viewer.getUniqueId()).size(),
                "limit", plugin.auctions().listingLimit(viewer),
                "knock", plugin.market().knockIntervalMs / 60000, "knocks", plugin.market().knockCount);
    }

    private String duration(long ms) {
        return AuctionIcons.remaining(plugin, ms);
    }

    /** 等聊天輸入：先把介面裡的物品存進草稿再關閉，輸入完重新打開（物品放回）。 */
    private void askKeeping(String key, Map<String, String> vars, java.util.function.Consumer<String> handler) {
        if (viewer.getItemOnCursor() != null && !viewer.getItemOnCursor().getType().isAir()) {
            plugin.messages().send(viewer, "auction.create.cursor");
            return;
        }
        stash();
        ask(key, vars, text -> {
            handler.accept(text);
            reopen();
        });
    }

    /** 介面裡的物品移到草稿（介面清空）。 */
    private void stash() {
        for (int i = 0; i < 27; i++) {
            ItemStack it = inv().getItem(i);
            draft.items[i] = it == null || it.getType().isAir() ? null : it;
            inv().setItem(i, null);
        }
    }

    private void confirm() {
        if (viewer.getItemOnCursor() != null && !viewer.getItemOnCursor().getType().isAir()) {
            plugin.messages().send(viewer, "auction.create.cursor");
            return;
        }
        ItemStack first = null;
        for (int i = 0; i < 27 && first == null; i++) {
            ItemStack it = inv().getItem(i);
            if (it != null && !it.getType().isAir()) {
                first = it.clone();
            }
        }
        stash();
        Map<String, String> out = new java.util.HashMap<>();
        if (plugin.auctions().create(viewer, draft, out)) {
            finished = true;
            new clre20.aetherMart.gui.ResultMenu(plugin, viewer, "listed", out, first,
                    () -> new AuctionHomeMenu(plugin, viewer).open(), false).open();
            return;
        }
        plugin.sounds().fail(viewer);
        for (int i = 0; i < 27; i++) {
            if (draft.items[i] != null) {
                inv().setItem(i, draft.items[i]);
                draft.items[i] = null;
            }
        }
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        int raw = e.getRawSlot();
        int top = 36;
        if (e.getClick() != ClickType.LEFT || !ALLOWED.contains(e.getAction())) {
            e.setCancelled(true);
            return;
        }
        if (raw >= 27 && raw < top) {
            super.onClick(e);
            return;
        }
        if (raw < 27) {
            ItemStack cursor = e.getCursor();
            if (cursor != null && !cursor.getType().isAir() && plugin.banned().isBanned(cursor)) {
                e.setCancelled(true);
                plugin.messages().send(viewer, "auction.create.banned", Vars.of("item", plugin.names().name(cursor)));
                plugin.sounds().fail(viewer);
            }
        }
        // 背包與上方物品區：允許一般的左鍵拿起／放下
        if (!e.isCancelled()) {
            queueConfirmUpdate();
        }
    }

    @Override
    public void onDrag(InventoryDragEvent e) {
        e.setCancelled(true);
    }

    @Override
    public void onClose() {
        if (finished) {
            return;
        }
        finished = true;
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            ItemStack it = inv().getItem(i);
            if (it != null && !it.getType().isAir()) {
                items.add(it);
            }
            inv().setItem(i, null);
        }
        for (int i = 0; i < 27; i++) {
            if (draft.items[i] != null) {
                items.add(draft.items[i]);
                draft.items[i] = null;
            }
        }
        giveBack(items);
    }

    @Override
    protected void onAbandon() {
        finished = true;
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            if (draft.items[i] != null) {
                items.add(draft.items[i]);
                draft.items[i] = null;
            }
        }
        giveBack(items);
    }

    private void giveBack(List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        List<ItemStack> left = viewer.isOnline() ? Inventories.giveAll(viewer.getInventory(), items) : items;
        if (!left.isEmpty()) {
            plugin.claims().add(ClaimEntry.items(viewer.getUniqueId(), new ArrayList<>(left), ClaimEntry.Source.RETURNED));
            if (viewer.isOnline()) {
                plugin.messages().send(viewer, "auction.create.returned-claim");
            }
        }
    }
}
