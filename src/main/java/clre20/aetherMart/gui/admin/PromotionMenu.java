package clre20.aetherMart.gui.admin;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.currency.CurrencyRegistry;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.shop.ShopModel;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/** 促銷排程：列出進行中／即將開始／已結束，新增（開始、結束、折扣），點兩下刪除。改動要回商品頁按儲存才生效。 */
public final class PromotionMenu extends Menu {

    private final ProductEditMenu parent;
    private String pendingDelete;
    private long deleteUntil;

    public PromotionMenu(AetherMart plugin, Player viewer, ProductEditMenu parent) {
        super(plugin, viewer);
        this.parent = parent;
    }

    @Override
    public String getMenuId() {
        return "admin_shop_promotions";
    }

    @Override
    protected String titleKey() {
        return "gui.admin.promotions.title";
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void build() {
        ShopModel.Product p = parent.draft();
        Currency c = plugin.currencies().get(p.currency);
        p.promotions.sort(Comparator.comparingLong(ShopModel.Promotion::start));
        long now = System.currentTimeMillis();
        int slot = 0;
        for (ShopModel.Promotion pr : p.promotions) {
            if (slot >= 45) {
                break;
            }
            String state = plain(pr.active(now) ? "gui.admin.promotions.active" : now < pr.start() ? "gui.admin.promotions.upcoming"
                    : "gui.admin.promotions.ended", Map.of());
            String discount = pr.percent() != null && pr.percent().signum() > 0
                    ? plain("gui.admin.promotions.percent", Vars.of("percent", pr.percent().stripTrailingZeros().toPlainString()))
                    : plain("gui.admin.promotions.fixed", Vars.of("price", c == null || pr.price() == null ? "" : c.format(pr.price())));
            boolean confirming = pr.id().equals(pendingDelete) && now < deleteUntil;
            Map<String, String> vars = Vars.of("state", state, "discount", discount,
                    "start", TimeUtil.format(pr.start(), plugin.config().zone, TimeUtil.DATE_TIME_SHORT),
                    "end", TimeUtil.format(pr.end(), plugin.config().zone, TimeUtil.DATE_TIME_SHORT));
            set(slot++, plugin.icons().of(pr.active(now) ? Material.LIME_DYE : now < pr.start() ? Material.CLOCK : Material.GRAY_DYE,
                    confirming ? "gui.admin.promotions.entry-confirm" : "gui.admin.promotions.entry", vars), e -> {
                if (!pr.id().equals(pendingDelete) || System.currentTimeMillis() >= deleteUntil) {
                    pendingDelete = pr.id();
                    deleteUntil = System.currentTimeMillis() + 5000;
                    refresh();
                    return;
                }
                p.promotions.remove(pr);
                pendingDelete = null;
                refresh();
            });
        }
        for (int i = 45; i < 54; i++) {
            set(i, plugin.icons().filler());
        }
        set(45, plugin.icons().of(Material.LIME_DYE, "gui.admin.promotions.add"), e -> {
            if (p.promotions.size() >= 45) {
                plugin.messages().send(viewer, "admin.promo.too-many");
                return;
            }
            askStart();
        });
        set(47, plugin.icons().of(Material.BOOK, "gui.admin.promotions.help"));
        set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> parent.reopen());
    }

    private void askStart() {
        ask("admin.input.promo-start", Vars.of("example", TimeUtil.format(System.currentTimeMillis(), plugin.config().zone, TimeUtil.DATE_TIME_SHORT)), text -> {
            long start = TimeUtil.parseDateTime(text, plugin.config().zone);
            if (start < 0) {
                plugin.messages().send(viewer, "admin.promo.bad-time");
                reopen();
                return;
            }
            ask("admin.input.promo-end", Map.of(), text2 -> {
                long end = TimeUtil.parseDateTime(text2, plugin.config().zone);
                if (end < 0 || end <= start || end <= System.currentTimeMillis()) {
                    plugin.messages().send(viewer, "admin.promo.bad-range");
                    reopen();
                    return;
                }
                ask("admin.input.promo-discount", Map.of(), text3 -> {
                    addPromotion(start, end, text3.trim());
                    reopen();
                });
            });
        });
    }

    private void addPromotion(long start, long end, String input) {
        ShopModel.Product p = parent.draft();
        Currency c = plugin.currencies().get(p.currency);
        if (c == null) {
            return;
        }
        BigDecimal min = plugin.market().shopMin(p.currency);
        ShopModel.Promotion pr;
        if (input.endsWith("%")) {
            BigDecimal pct;
            try {
                pct = new BigDecimal(input.substring(0, input.length() - 1).trim());
            } catch (NumberFormatException e) {
                plugin.messages().send(viewer, "admin.promo.bad-discount");
                return;
            }
            if (pct.signum() <= 0 || pct.compareTo(BigDecimal.valueOf(100)) >= 0 || pct.scale() > 2) {
                plugin.messages().send(viewer, "admin.promo.bad-discount");
                return;
            }
            BigDecimal after = p.price.multiply(BigDecimal.valueOf(100).subtract(pct)).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            if (c.round(after).compareTo(min) < 0) {
                plugin.messages().send(viewer, "admin.shop.below-min", Vars.of("min", c.format(min)));
                return;
            }
            pr = new ShopModel.Promotion(UUID.randomUUID().toString().substring(0, 8), start, end, pct, null);
        } else {
            BigDecimal price = CurrencyRegistry.parseAmount(input, c, plugin.config().maxAmount);
            if (price == null) {
                plugin.messages().send(viewer, "admin.promo.bad-discount");
                return;
            }
            if (price.compareTo(min) < 0) {
                plugin.messages().send(viewer, "admin.shop.below-min", Vars.of("min", c.format(min)));
                return;
            }
            pr = new ShopModel.Promotion(UUID.randomUUID().toString().substring(0, 8), start, end, BigDecimal.ZERO, price);
        }
        for (ShopModel.Promotion o : p.promotions) {
            if (o.overlaps(pr)) {
                plugin.messages().send(viewer, "admin.promo.overlap");
                return;
            }
        }
        p.promotions.add(pr);
        plugin.messages().send(viewer, "admin.promo.added");
    }
}
