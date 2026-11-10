package clre20.aetherMart.gui;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.gui.auction.ClaimBoxMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * 交易結果（3 排）：送出成功後切到這裡，玩家不用在聊天室找訊息。
 * 4 = 這次交易的物品，13 = 結果與明細，20 = 返回（依類型：繼續購買、返回拍賣場…），22 = 前往領取箱（有放進領取箱時），24 = 回主選單。
 * 文字在 messages.yml 的 gui.result.<type>.*。
 */
public final class ResultMenu extends Menu {

    private final String type;
    private final Map<String, String> vars;
    private final ItemStack item;
    private final Runnable back;
    private final boolean claimShortcut;

    /**
     * @param type          shop、auction-buy、bid、listed
     * @param back          「返回」按鈕的動作
     * @param claimShortcut 物品是否放進了領取箱（顯示「前往領取箱」）
     */
    public ResultMenu(AetherMart plugin, Player viewer, String type, Map<String, String> vars, ItemStack item,
                      Runnable back, boolean claimShortcut) {
        super(plugin, viewer);
        this.type = type;
        this.vars = vars;
        this.item = item;
        this.back = back;
        this.claimShortcut = claimShortcut;
    }

    @Override
    public String getMenuId() {
        return "result_" + type;
    }

    @Override
    protected String titleKey() {
        return "gui.result.title";
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected void build() {
        for (int i = 0; i < 27; i++) {
            set(i, plugin.icons().filler());
        }
        if (item != null && !item.getType().isAir()) {
            set(4, item.clone());
        }
        String base = "gui.result." + type;
        set(13, plugin.icons().glow(plugin.icons().of(type.equals("bid") ? Material.GOLD_BLOCK : Material.EMERALD_BLOCK, base + ".status", vars), true));
        set(20, plugin.icons().of(Material.ARROW, base + ".back", vars), e -> back.run());
        if (claimShortcut) {
            set(22, plugin.icons().of(Material.ENDER_CHEST, "gui.result.claims", Map.of(
                    "count", String.valueOf(plugin.claims().count(viewer.getUniqueId())))), e -> new ClaimBoxMenu(plugin, viewer, 0).open());
        }
        set(24, plugin.icons().of(Material.OAK_DOOR, "gui.result.hub"), e -> new HubMenu(plugin, viewer).open());
    }
}
