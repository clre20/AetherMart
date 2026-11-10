package clre20.aetherMart.player;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 交易前的共同檢查：資料是否載入、是否受罰、欠款自動扣、Discord 綁定。 */
public final class TradeGuard {

    private final AetherMart plugin;

    public TradeGuard(AetherMart plugin) {
        this.plugin = plugin;
    }

    /**
     * 檢查玩家能否買賣；不行時已送出訊息並回傳 false。
     *
     * @param buying true = 購買／出價；false = 上架
     */
    public boolean check(Player p, boolean buying) {
        PlayerData d = plugin.players().get(p.getUniqueId());
        if (d == null) {
            plugin.messages().send(p, "common.loading");
            return false;
        }
        long now = System.currentTimeMillis();
        if (d.banned(now)) {
            plugin.messages().send(p, "penalty.banned", Vars.of(
                    "until", TimeUtil.format(d.bannedUntil, plugin.config().zone, TimeUtil.DATE_TIME_SHORT),
                    "debt", debtText(d)));
            return false;
        }
        if (d.hasDebt() && !payDebts(p, d)) {
            plugin.messages().send(p, "penalty.debt", Vars.of("debt", debtText(d)));
            return false;
        }
        if (!plugin.links().allowed(p, buying)) {
            plugin.messages().send(p, plugin.links().available() ? "link.required" : "link.unavailable");
            return false;
        }
        return true;
    }

    /** 嘗試扣欠款；全部付清回傳 true。 */
    private boolean payDebts(Player p, PlayerData d) {
        for (Map.Entry<String, BigDecimal> e : new ArrayList<>(d.debts.entrySet())) {
            Currency c = plugin.currencies().get(e.getKey());
            if (c == null || !c.available()) {
                continue;
            }
            BigDecimal debt = e.getValue();
            BigDecimal bal = c.round(c.provider().balance(p));
            BigDecimal pay = bal.min(debt);
            if (pay.signum() > 0 && c.provider().withdraw(p, pay)) {
                BigDecimal left = debt.subtract(pay);
                plugin.players().setDebt(p.getUniqueId(), e.getKey(), left);
                plugin.activity().log("PENALTY", p.getName() + " paid debt " + c.format(pay) + ", left " + c.format(left.max(BigDecimal.ZERO)));
                if (left.signum() <= 0) {
                    plugin.messages().send(p, "penalty.debt-paid", Vars.of("amount", c.format(pay)));
                }
            }
        }
        return !d.hasDebt();
    }

    public String debtText(PlayerData d) {
        if (!d.hasDebt()) {
            return plugin.messages().plain("penalty.no-debt", Map.of());
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : d.debts.entrySet()) {
            Currency c = plugin.currencies().get(e.getKey());
            parts.add(c == null ? e.getValue().toPlainString() + " " + e.getKey() : c.format(e.getValue()));
        }
        return String.join("、", parts);
    }
}
