package clre20.aetherMart.gui;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.util.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 介面圖示：名稱與說明都來自 messages.yml（gui.*.name / gui.*.lore）。 */
public final class Icons {

    private final AetherMart plugin;

    public Icons(AetherMart plugin) {
        this.plugin = plugin;
    }

    /** base 為 messages 的路徑前綴，會讀 base.name 與 base.lore。 */
    public ItemStack of(Material material, String base, Map<String, String> vars) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.item(plugin.messages().raw(base + ".name"), vars));
        List<String> lore = plugin.messages().list(base + ".lore");
        if (!lore.isEmpty()) {
            meta.lore(Texts.itemLines(lore, vars));
        }
        hide(meta);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack of(Material material, String base) {
        return of(material, base, Map.of());
    }

    /** 灰色玻璃片填充。 */
    public ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    /** 在物品原本的說明後面加上幾行（顯示用複本）。 */
    public ItemStack withLore(ItemStack original, List<String> templates, Map<String, String> vars) {
        ItemStack item = original.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.addAll(Texts.itemLines(templates, vars));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack glow(ItemStack item, boolean on) {
        if (!on) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    private static void hide(ItemMeta meta) {
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
    }
}
