package clre20.aetherMart.gui.auction;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.data.TradeRecord;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.gui.Paging;
import clre20.aetherMart.gui.admin.AdminHomeMenu;
import clre20.aetherMart.util.Texts;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 交易紀錄（新到舊分頁）。玩家版只看自己；管理員版（admin=true）可依玩家搜尋、依類型篩選。
 * 資料在資料庫執行緒查詢，查好才開啟。
 */
public final class HistoryMenu extends Menu {

    private static final TradeRecord.Type[] FILTERS = {null, TradeRecord.Type.SHOP_BUY, TradeRecord.Type.SALE, TradeRecord.Type.SALE_BID,
            TradeRecord.Type.BID, TradeRecord.Type.LIST, TradeRecord.Type.CANCEL, TradeRecord.Type.FORCE_REMOVE, TradeRecord.Type.EXPIRE,
            TradeRecord.Type.PENALTY};

    private final boolean admin;
    private UUID player;
    private String playerName;
    private TradeRecord.Type type;
    private int page;
    private List<TradeRecord> rows = List.of();
    private boolean loaded;

    /** 玩家版：player 傳 null 代表自己。 */
    public HistoryMenu(AetherMart plugin, Player viewer, UUID player, int page) {
        this(plugin, viewer, false, player == null ? viewer.getUniqueId() : player, null, page);
    }

    public HistoryMenu(AetherMart plugin, Player viewer, boolean admin, UUID player, String playerName, int page) {
        super(plugin, viewer);
        this.admin = admin;
        this.player = admin ? player : viewer.getUniqueId();
        this.playerName = playerName;
        this.page = page;
    }

    @Override
    public String getMenuId() {
        return admin ? "admin_log" : "history";
    }

    @Override
    protected String titleKey() {
        return admin ? "gui.log.title" : "gui.history.title";
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    public void open() {
        loaded = false;
        plugin.trades().query(player, type, page * Paging.PER_PAGE, Paging.PER_PAGE + 1).whenComplete((list, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline()) {
                        return;
                    }
                    rows = err == null ? list : List.of();
                    loaded = true;
                    super.open();
                }));
    }

    @Override
    protected void build() {
        for (int i = 45; i < 54; i++) {
            set(i, plugin.icons().filler());
        }
        if (!loaded) {
            return;
        }
        int n = Math.min(Paging.PER_PAGE, rows.size());
        for (int i = 0; i < n; i++) {
            set(i, icon(rows.get(i)));
        }
        if (n == 0) {
            set(22, plugin.icons().of(Material.BARRIER, "gui.history.empty"));
        }
        if (page > 0) {
            set(45, plugin.icons().of(Material.ARROW, "gui.common.prev", Vars.of("page", page)), e -> {
                page--;
                open();
            });
        }
        if (admin) {
            set(47, plugin.icons().of(Material.HOPPER, "gui.log.filter", Vars.of("value", type == null
                    ? plain("gui.auction.filter.all", Map.of()) : plain("trade.type." + type.name().toLowerCase(), Map.of()))), e -> {
                int i = 0;
                for (int k = 0; k < FILTERS.length; k++) {
                    if (FILTERS[k] == type) {
                        i = k;
                    }
                }
                type = FILTERS[(i + 1) % FILTERS.length];
                page = 0;
                open();
            });
            set(48, plugin.icons().of(Material.PLAYER_HEAD, player == null ? "gui.log.search" : "gui.log.search-active",
                    Vars.of("value", playerName == null ? "" : playerName)), e -> {
                if (player != null) {
                    player = null;
                    playerName = null;
                    page = 0;
                    open();
                    return;
                }
                ask("admin.input.player", Map.of(), text -> {
                    String name = Texts.sanitizeInput(text, 16);
                    OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
                    if (op == null) {
                        plugin.messages().send(viewer, "common.unknown-player", Vars.of("player", name));
                    } else {
                        player = op.getUniqueId();
                        playerName = op.getName() == null ? name : op.getName();
                        page = 0;
                    }
                    open();
                });
            });
            set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new AdminHomeMenu(plugin, viewer).open());
        } else {
            set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new AuctionHomeMenu(plugin, viewer).open());
        }
        if (rows.size() > Paging.PER_PAGE) {
            set(53, plugin.icons().of(Material.ARROW, "gui.common.next", Vars.of("page", page + 2)), e -> {
                page++;
                open();
            });
        }
    }

    private ItemStack icon(TradeRecord r) {
        Currency c = plugin.currencies().get(r.currency());
        String amount;
        try {
            amount = c == null ? r.amount() + " " + r.currency() : c.format(new BigDecimal(r.amount()));
        } catch (RuntimeException e) {
            amount = r.amount();
        }
        Material m = switch (r.type()) {
            case SHOP_BUY -> Material.EMERALD;
            case SALE, SALE_BID -> Material.GOLD_INGOT;
            case BID -> Material.GOLD_NUGGET;
            case LIST -> Material.WRITABLE_BOOK;
            case PENALTY -> Material.REDSTONE;
            default -> Material.PAPER;
        };
        Map<String, String> vars = Vars.of("type", plain("trade.type." + r.type().name().toLowerCase(), Map.of()),
                "time", TimeUtil.format(r.time(), plugin.config().zone, TimeUtil.DATE_TIME),
                "actor", r.actorName() == null ? "-" : r.actorName(), "other", r.otherName() == null ? "-" : r.otherName(),
                "amount", amount, "items", r.summary() == null ? "" : r.summary(),
                "auction", r.auctionId() > 0 ? "#A-" + r.auctionId() : "-", "receipt", r.receipt() == null || r.receipt().isEmpty() ? "-" : r.receipt());
        return plugin.icons().of(m, "gui.history.entry", vars);
    }
}
