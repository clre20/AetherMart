package clre20.aetherMart.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 背包空間計算與給物品。 */
public final class Inventories {

    private Inventories() {
    }

    /** 背包主欄位（不含裝備、副手）能不能放下 amount 個 item。 */
    public static boolean fits(PlayerInventory inv, ItemStack item, int amount) {
        return space(inv, item) >= amount;
    }

    public static int space(PlayerInventory inv, ItemStack item) {
        int max = item.getMaxStackSize();
        int space = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) {
                space += max;
            } else if (s.isSimilar(item)) {
                space += Math.max(0, max - s.getAmount());
            }
        }
        return space;
    }

    /** 給 amount 個 item，放不下的回傳。 */
    public static List<ItemStack> give(PlayerInventory inv, ItemStack item, int amount) {
        List<ItemStack> stacks = new ArrayList<>();
        int max = item.getMaxStackSize();
        int left = amount;
        while (left > 0) {
            ItemStack s = item.clone();
            int n = Math.min(max, left);
            s.setAmount(n);
            stacks.add(s);
            left -= n;
        }
        Map<Integer, ItemStack> over = inv.addItem(stacks.toArray(new ItemStack[0]));
        return new ArrayList<>(over.values());
    }

    /** 把物品還給玩家，放不下的回傳。 */
    public static List<ItemStack> giveAll(PlayerInventory inv, List<ItemStack> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<Integer, ItemStack> over = inv.addItem(items.stream().map(ItemStack::clone).toArray(ItemStack[]::new));
        return new ArrayList<>(over.values());
    }
}
