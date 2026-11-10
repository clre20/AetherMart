package clre20.aetherMart.gui.admin;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.shop.ShopModel;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.Map;

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
            new PromotionCreateMenu(plugin, viewer, parent, this).open();
        });
        set(47, plugin.icons().of(Material.BOOK, "gui.admin.promotions.help"));
        set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> parent.reopen());
    }
}
