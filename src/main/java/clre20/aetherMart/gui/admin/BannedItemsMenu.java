package clre20.aetherMart.gui.admin;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.auction.BannedItems;
import clre20.aetherMart.gui.Menu;
import clre20.aetherMart.gui.Paging;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 禁止販售清單：點自己背包的物品加入（比對完整 NBT），清單中的物品點兩下移除。 */
public final class BannedItemsMenu extends Menu {

    private int page;
    private BannedItems.Entry pending;
    private long until;

    public BannedItemsMenu(AetherMart plugin, Player viewer, int page) {
        super(plugin, viewer);
        this.page = page;
    }

    @Override
    public String getMenuId() {
        return "admin_banned_items";
    }

    @Override
    protected String titleKey() {
        return "gui.admin.banned.title";
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void build() {
        List<BannedItems.Entry> list = new ArrayList<>(plugin.banned().entries());
        page = Paging.clamp(page, list.size());
        int slot = 0;
        long now = System.currentTimeMillis();
        for (BannedItems.Entry en : Paging.slice(list, page)) {
            boolean confirming = en == pending && now < until;
            ItemStack icon = plugin.icons().withLore(en.item(), plugin.messages().list(confirming
                    ? "gui.admin.banned.entry-confirm" : "gui.admin.banned.entry"), Vars.of());
            set(slot++, icon, e -> {
                if (!AdminAuctionsMenu.allowed(plugin, viewer)) {
                    return;
                }
                if (pending != en || System.currentTimeMillis() >= until) {
                    pending = en;
                    until = System.currentTimeMillis() + 5000;
                    refresh();
                    return;
                }
                plugin.banned().remove(en);
                plugin.activity().log("ADMIN", viewer.getName() + " removed banned item " + plugin.names().name(en.item()));
                plugin.messages().send(viewer, "admin.banned.removed", Vars.of("item", plugin.names().name(en.item())));
                pending = null;
                refresh();
            });
        }
        for (int i = 45; i < 54; i++) {
            set(i, plugin.icons().filler());
        }
        set(47, plugin.icons().of(Material.BOOK, "gui.admin.banned.help"));
        if (page > 0) {
            set(45, plugin.icons().of(Material.ARROW, "gui.common.prev", Vars.of("page", page)), e -> {
                page--;
                refresh();
            });
        }
        set(49, plugin.icons().of(Material.OAK_DOOR, "gui.common.back"), e -> new AdminAuctionsMenu(plugin, viewer, 0).open());
        if (page < Paging.pages(list.size()) - 1) {
            set(53, plugin.icons().of(Material.ARROW, "gui.common.next", Vars.of("page", page + 2)), e -> {
                page++;
                refresh();
            });
        }
    }

    @Override
    protected void onBottomClick(InventoryClickEvent e) {
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType().isAir() || !AdminAuctionsMenu.allowed(plugin, viewer)) {
            return;
        }
        if (plugin.banned().entries().size() >= 1000) {
            plugin.messages().send(viewer, "admin.banned.too-many");
            return;
        }
        if (plugin.banned().add(it)) {
            plugin.activity().log("ADMIN", viewer.getName() + " banned item " + plugin.names().name(it) + " (" + it.getType() + ")");
            plugin.messages().send(viewer, "admin.banned.added", Vars.of("item", plugin.names().name(it)));
        } else {
            plugin.messages().send(viewer, "admin.banned.exists");
        }
        refresh();
    }
}
