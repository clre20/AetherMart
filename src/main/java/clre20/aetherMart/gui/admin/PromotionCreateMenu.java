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
import java.util.Map;
import java.util.UUID;

/**
 * 新增促銷（4 排）：開始時間預設「現在」，結束時間用快速按鈕（1 小時～30 天）一鍵設定，
 * 也可以在聊天室輸入日期（11/1、11/1 20:00…）或長度（3d、12h）；折扣輸入 20% 或促銷價。
 * 按確認後加進商品草稿，回商品頁按儲存才生效。
 */
public final class PromotionCreateMenu extends Menu {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private static final long[] PRESETS = {HOUR, 6 * HOUR, DAY, 3 * DAY, 7 * DAY, 14 * DAY, 30 * DAY};
    private static final int PRESET_SLOT = 19;

    private final ProductEditMenu product;
    private final PromotionMenu list;
    private long start;
    private long end = -1;
    /** 結束時間是用「長度」設定的（快速按鈕或輸入 3d）：改開始時間時一起移動。 */
    private long length = -1;
    private BigDecimal percent;
    private BigDecimal price;

    public PromotionCreateMenu(AetherMart plugin, Player viewer, ProductEditMenu product, PromotionMenu list) {
        super(plugin, viewer);
        this.product = product;
        this.list = list;
        long now = System.currentTimeMillis();
        this.start = now - now % 60_000L;
    }

    @Override
    public String getMenuId() {
        return "admin_shop_promotion_create";
    }

    @Override
    protected String titleKey() {
        return "gui.admin.promo-create.title";
    }

    @Override
    protected int rows() {
        return 4;
    }

    private String time(long t) {
        return TimeUtil.format(t, plugin.config().zone, TimeUtil.DATE_TIME_SHORT);
    }

    private String lengthText(long millis) {
        return TimeUtil.formatShort(millis, plain("time.day", Map.of()), plain("time.hour", Map.of()), plain("time.minute", Map.of()));
    }

    private String unset() {
        return plain("gui.admin.promo-create.unset", Map.of());
    }

    private String discountText(Currency c) {
        if (percent != null) {
            return plain("gui.admin.promotions.percent", Vars.of("percent", percent.stripTrailingZeros().toPlainString()));
        }
        if (price != null) {
            return plain("gui.admin.promotions.fixed", Vars.of("price", c.format(price)));
        }
        return unset();
    }

    private String afterText(ShopModel.Product p, Currency c) {
        if (percent != null) {
            return c.format(c.round(discounted(p)));
        }
        return price != null ? c.format(price) : unset();
    }

    private BigDecimal discounted(ShopModel.Product p) {
        return p.price.multiply(BigDecimal.valueOf(100).subtract(percent)).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
    }

    @Override
    protected void build() {
        ShopModel.Product p = product.draft();
        Currency c = plugin.currencies().get(p.currency);
        if (c == null) {
            return;
        }
        for (int i = 27; i < 36; i++) {
            set(i, plugin.icons().filler());
        }
        Map<String, String> vars = Vars.of(
                "start", time(start),
                "end", end < 0 ? unset() : time(end),
                "length", end < 0 ? unset() : lengthText(end - start),
                "discount", discountText(c),
                "original", c.format(p.price),
                "after", afterText(p, c));

        set(10, plugin.icons().of(Material.CLOCK, "gui.admin.promo-create.start", vars), e ->
                ask("admin.input.promo-start", Map.of(), text -> {
                    long t = TimeUtil.parseFlexible(text, plugin.config().zone, System.currentTimeMillis(), false);
                    if (t < 0) {
                        plugin.messages().send(viewer, "admin.promo.bad-time");
                    } else {
                        setStart(t);
                    }
                    reopen();
                }));
        set(11, plugin.icons().of(Material.SUNFLOWER, "gui.admin.promo-create.now", vars), e -> {
            long now = System.currentTimeMillis();
            setStart(now - now % 60_000L);
            refresh();
        });
        set(13, plugin.icons().of(Material.COMPASS, "gui.admin.promo-create.end", vars), e ->
                ask("admin.input.promo-end", Map.of(), text -> {
                    long d = TimeUtil.parseDurationMillis(text);
                    if (d > 0) {
                        length = d;
                        end = start + d;
                    } else {
                        long t = TimeUtil.parseFlexible(text, plugin.config().zone, System.currentTimeMillis(), true);
                        if (t < 0) {
                            plugin.messages().send(viewer, "admin.promo.bad-time");
                        } else if (t <= start) {
                            plugin.messages().send(viewer, "admin.promo.end-before-start", Vars.of("start", time(start)));
                        } else {
                            length = -1;
                            end = t;
                        }
                    }
                    reopen();
                }));
        set(15, plugin.icons().of(Material.GOLD_INGOT, "gui.admin.promo-create.discount", vars), e ->
                ask("admin.input.promo-discount", Vars.of("price", c.format(p.price)), text -> {
                    readDiscount(text.trim(), p, c);
                    reopen();
                }));

        for (int i = 0; i < PRESETS.length; i++) {
            long d = PRESETS[i];
            boolean selected = length == d;
            set(PRESET_SLOT + i, plugin.icons().glow(plugin.icons().of(selected ? Material.LIME_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE,
                    "gui.admin.promo-create.preset", Vars.of("length", lengthText(d), "end", time(start + d))), selected), e -> {
                length = d;
                end = start + d;
                refresh();
            });
        }

        set(27, plugin.icons().of(Material.OAK_DOOR, "gui.admin.promo-create.cancel"), e -> list.reopen());
        set(31, plugin.icons().of(Material.BOOK, "gui.admin.promo-create.help"));
        set(35, plugin.icons().of(Material.EMERALD_BLOCK, "gui.admin.promo-create.confirm", vars), e -> {
            if (add(p, c)) {
                list.reopen();
            } else {
                plugin.sounds().fail(viewer);
            }
        });
    }

    private void setStart(long t) {
        start = t;
        if (length > 0) {
            end = start + length;
        } else if (end >= 0 && end <= start) {
            end = -1;
        }
    }

    private void readDiscount(String input, ShopModel.Product p, Currency c) {
        BigDecimal min = plugin.market().shopMin(p.currency);
        String s = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKC).trim();
        if (s.endsWith("%")) {
            String num = s.substring(0, s.length() - 1).trim();
            if (num.isEmpty() || num.length() > 6 || !num.matches("\\d+(\\.\\d{1,2})?")) {
                plugin.messages().send(viewer, "admin.promo.bad-discount");
                return;
            }
            BigDecimal pct = new BigDecimal(num);
            if (pct.signum() <= 0 || pct.compareTo(BigDecimal.valueOf(100)) >= 0) {
                plugin.messages().send(viewer, "admin.promo.bad-discount");
                return;
            }
            BigDecimal old = percent;
            percent = pct;
            if (c.round(discounted(p)).compareTo(min) < 0) {
                percent = old;
                plugin.messages().send(viewer, "admin.shop.below-min", Vars.of("min", c.format(min)));
                return;
            }
            price = null;
            return;
        }
        BigDecimal v = CurrencyRegistry.parseAmount(s, c, plugin.config().maxAmount);
        if (v == null) {
            plugin.messages().send(viewer, "admin.promo.bad-discount");
            return;
        }
        if (v.compareTo(min) < 0) {
            plugin.messages().send(viewer, "admin.shop.below-min", Vars.of("min", c.format(min)));
            return;
        }
        price = v;
        percent = null;
    }

    private boolean add(ShopModel.Product p, Currency c) {
        if (end < 0) {
            plugin.messages().send(viewer, "admin.promo.no-end");
            return false;
        }
        if (percent == null && price == null) {
            plugin.messages().send(viewer, "admin.promo.no-discount");
            return false;
        }
        if (end <= start) {
            plugin.messages().send(viewer, "admin.promo.end-before-start", Vars.of("start", time(start)));
            return false;
        }
        if (end <= System.currentTimeMillis()) {
            plugin.messages().send(viewer, "admin.promo.end-past");
            return false;
        }
        if (p.promotions.size() >= 45) {
            plugin.messages().send(viewer, "admin.promo.too-many");
            return false;
        }
        ShopModel.Promotion pr = new ShopModel.Promotion(UUID.randomUUID().toString().substring(0, 8), start, end,
                percent != null ? percent : BigDecimal.ZERO, percent != null ? null : price);
        for (ShopModel.Promotion o : p.promotions) {
            if (o.overlaps(pr)) {
                plugin.messages().send(viewer, "admin.promo.overlap", Vars.of("start", time(o.start()), "end", time(o.end())));
                return false;
            }
        }
        p.promotions.add(pr);
        plugin.messages().send(viewer, "admin.promo.added", Vars.of("start", time(start), "end", time(end), "discount", discountText(c)));
        return true;
    }
}
