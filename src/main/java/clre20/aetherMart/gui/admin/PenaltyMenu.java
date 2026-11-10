package clre20.aetherMart.gui.admin;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.player.PlayerService;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 受罰名單：禁令中或有欠款的玩家；點兩下解除（含欠款）。 */
public final class PenaltyMenu extends Menu {

    private List<PlayerService.PenaltyRow> rows = List.of();
    private boolean loaded;
    private UUID pending;
    private long until;

    public PenaltyMenu(AetherMart plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    public String getMenuId() {
        return "admin_penalties";
    }

    @Override
    protected String titleKey() {
        return "gui.admin.penalties.title";
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    public void open() {
        plugin.players().penalties(System.currentTimeMillis()).whenComplete((list, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
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
        set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new AdminAuctionsMenu(plugin, viewer, 0).open());
        if (!loaded) {
            return;
        }
        long now = System.currentTimeMillis();
        int slot = 0;
        for (PlayerService.PenaltyRow r : rows) {
            if (slot >= 45) {
                break;
            }
            List<String> debts = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> d : r.debts().entrySet()) {
                Currency c = plugin.currencies().get(d.getKey());
                debts.add(c == null ? d.getValue().toPlainString() + " " + d.getKey() : c.format(d.getValue()));
            }
            boolean confirming = r.player().equals(pending) && now < until;
            Map<String, String> vars = Vars.of("player", r.name(),
                    "until", r.until() > now ? TimeUtil.format(r.until(), plugin.config().zone, TimeUtil.DATE_TIME_SHORT)
                            : plain("gui.admin.penalties.expired", Map.of()),
                    "debt", debts.isEmpty() ? plain("penalty.no-debt", Map.of()) : String.join("、", debts),
                    "reason", r.reason() == null ? "" : r.reason());
            set(slot++, plugin.icons().of(Material.PLAYER_HEAD, confirming ? "gui.admin.penalties.entry-confirm" : "gui.admin.penalties.entry", vars), e -> {
                if (!AdminAuctionsMenu.allowed(plugin, viewer)) {
                    return;
                }
                if (!r.player().equals(pending) || System.currentTimeMillis() >= until) {
                    pending = r.player();
                    until = System.currentTimeMillis() + 5000;
                    refresh();
                    return;
                }
                plugin.players().lift(r.player());
                plugin.activity().log("ADMIN", viewer.getName() + " lifted penalty and debts of " + r.name() + " (" + r.player() + ")");
                plugin.messages().send(viewer, "admin.penalty.lifted", Vars.of("player", r.name()));
                pending = null;
                open();
            });
        }
        if (rows.isEmpty()) {
            set(22, plugin.icons().of(Material.BARRIER, "gui.admin.penalties.empty"));
        }
    }
}
