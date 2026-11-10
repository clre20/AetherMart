package clre20.aetherMart.gui.auction;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.gui.Paging;
import clre20.aetherMart.player.ClaimEntry;
import clre20.aetherMart.player.ClaimService;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 領取箱：一格一筆（一整包物品或一筆錢），左鍵領取；下排全部領取。 */
public final class ClaimBoxMenu extends Menu {

    private int page;

    public ClaimBoxMenu(AetherMart plugin, Player viewer, int page) {
        super(plugin, viewer);
        this.page = page;
    }

    @Override
    public String getMenuId() {
        return "claim_box";
    }

    @Override
    protected String titleKey() {
        return "gui.claims.title";
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void build() {
        List<ClaimEntry> list = plugin.claims().list(viewer.getUniqueId());
        for (int i = 45; i < 54; i++) {
            set(i, plugin.icons().filler());
        }
        set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new AuctionHomeMenu(plugin, viewer).open());
        if (list == null) {
            set(22, plugin.icons().of(Material.CLOCK, "gui.common.loading"));
            return;
        }
        List<ClaimEntry> copy = new ArrayList<>(list);
        page = Paging.clamp(page, copy.size());
        int slot = 0;
        for (ClaimEntry c : Paging.slice(copy, page)) {
            set(slot++, icon(c), e -> {
                handle(plugin.claims().claim(viewer, c));
                refresh();
            });
        }
        if (copy.isEmpty()) {
            set(22, plugin.icons().of(Material.BARRIER, "gui.claims.empty"));
        } else {
            set(47, plugin.icons().of(Material.HOPPER, "gui.claims.all", Vars.of("count", copy.size())), e -> {
                for (ClaimEntry c : new ArrayList<>(copy)) {
                    ClaimService.Result r = plugin.claims().claim(viewer, c);
                    if (r == ClaimService.Result.NO_SPACE || r == ClaimService.Result.PARTIAL) {
                        handle(r);
                        break;
                    }
                }
                refresh();
            });
        }
        if (page > 0) {
            set(45, plugin.icons().of(Material.ARROW, "gui.common.prev", Vars.of("page", page)), e -> {
                page--;
                refresh();
            });
        }
        if (page < Paging.pages(copy.size()) - 1) {
            set(53, plugin.icons().of(Material.ARROW, "gui.common.next", Vars.of("page", page + 2)), e -> {
                page++;
                refresh();
            });
        }
    }

    private void handle(ClaimService.Result r) {
        switch (r) {
            case OK -> {
                plugin.messages().send(viewer, "claim.claimed");
                plugin.sounds().success(viewer);
            }
            case PARTIAL -> plugin.messages().send(viewer, "claim.partial");
            case NO_SPACE -> {
                plugin.messages().send(viewer, "claim.no-space");
                plugin.sounds().fail(viewer);
            }
            case UNAVAILABLE -> {
                plugin.messages().send(viewer, "claim.unavailable");
                plugin.sounds().fail(viewer);
            }
            case BUSY -> plugin.messages().send(viewer, "common.loading");
        }
    }

    private ItemStack icon(ClaimEntry c) {
        Map<String, String> vars = Vars.of("source", plain("claim.source." + c.source.name().toLowerCase(), Map.of()),
                "time", TimeUtil.format(c.created, plugin.config().zone, TimeUtil.DATE_TIME_SHORT));
        if (c.kind == ClaimEntry.Kind.MONEY) {
            Currency cur = plugin.currencies().get(c.currency);
            vars.put("amount", cur == null ? c.amount.toPlainString() + " " + c.currency : cur.format(c.amount));
            return plugin.icons().of(Material.GOLD_INGOT, "gui.claims.money", vars);
        }
        int n = 0;
        for (ItemStack i : c.items) {
            n += i.getAmount();
        }
        vars.put("count", String.valueOf(n));
        vars.put("slots", String.valueOf(c.items.size()));
        vars.put("summary", plugin.claims().describe(c));
        ItemStack first = c.items.isEmpty() ? new ItemStack(Material.CHEST) : c.items.get(0);
        ItemStack icon = plugin.icons().withLore(first, plugin.messages().list("gui.claims.items.lore"), vars);
        icon.setAmount(Math.max(1, Math.min(icon.getMaxStackSize(), first.getAmount())));
        return icon;
    }
}
