package clre20.aetherMart.gui;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.util.Texts;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 所有箱子介面的基底。簽署：InventoryHolder 提供 getPlugin()（回傳 AetherMart）與 getMenuId()，
 * 每個圖示寫入 PDC aethermart:gui = 介面 ID，讓其他插件（例如 Item-lock）辨識。只接受左鍵。
 */
public abstract class Menu implements InventoryHolder {

    public static final String SIGNATURE = "clre20:aethermart";

    protected final AetherMart plugin;
    protected final Player viewer;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();
    private Inventory inventory;
    /** 正在等聊天輸入：關閉介面時不要觸發 onClose 的收尾。 */
    boolean suspended;

    protected Menu(AetherMart plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    public final Plugin getPlugin() {
        return plugin;
    }

    /** 介面 ID，例如 auction_create、shop_category。 */
    public abstract String getMenuId();

    protected abstract String titleKey();

    protected Map<String, String> titleVars() {
        return Map.of();
    }

    protected abstract int rows();

    protected abstract void build();

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public final Player viewer() {
        return viewer;
    }

    public void open() {
        inventory = Bukkit.createInventory(this, rows() * 9, title());
        build();
        suspended = false;
        viewer.openInventory(inventory);
    }

    /**
     * 介面標題。基岩版玩家（config.yml bedrock.fixed-title）一律用同一個標題：
     * Geyser 只有在「標題、大小相同」時才會沿用已開著的箱子，否則每換一頁都要關掉再開（畫面會閃一下）。
     */
    private net.kyori.adventure.text.Component title() {
        if (plugin.config().bedrockFixedTitle && clre20.aetherMart.util.Bedrock.is(viewer)) {
            return plugin.messages().get("gui.bedrock-title", Map.of());
        }
        return plugin.messages().get(titleKey(), titleVars());
    }

    /** 重畫內容（不重新開啟）。 */
    public void refresh() {
        if (inventory == null) {
            return;
        }
        actions.clear();
        clearButtons();
        build();
    }

    /** 清掉按鈕（預設清空整個介面；建立拍賣介面只清下排）。 */
    protected void clearButtons() {
        inventory.clear();
    }

    protected final Inventory inv() {
        return inventory;
    }

    protected final void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, sign(item));
        if (action != null) {
            actions.put(slot, action);
        } else {
            actions.remove(slot);
        }
    }

    protected final void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    /** 在圖示上寫入插件簽署。 */
    protected ItemStack sign(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, getMenuId());
            item.setItemMeta(meta);
        }
        return item;
    }

    public static NamespacedKey key(Plugin plugin) {
        return new NamespacedKey(plugin, "gui");
    }

    /** 點擊：預設全部取消，只有上方介面的左鍵會觸發按鈕。 */
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClick() != ClickType.LEFT) {
            return;
        }
        if (e.getClickedInventory() == null || !Objects.equals(e.getClickedInventory(), inventory)) {
            onBottomClick(e);
            return;
        }
        Consumer<InventoryClickEvent> a = actions.get(e.getRawSlot());
        if (a != null) {
            plugin.sounds().click(viewer);
            a.accept(e);
        }
    }

    /** 點自己背包的物品（左鍵）；預設不做事。 */
    protected void onBottomClick(InventoryClickEvent e) {
    }

    public void onDrag(InventoryDragEvent e) {
        e.setCancelled(true);
    }

    /** 介面關閉（不含等聊天輸入時的暫時關閉）。 */
    public void onClose() {
    }

    /** 等聊天輸入：暫時關閉介面，輸入完或取消後重新開啟。 */
    protected void ask(String promptKey, Map<String, String> vars, Consumer<String> onInput) {
        suspended = true;
        viewer.closeInventory();
        plugin.input().prompt(viewer, promptKey, vars, text -> {
            onInput.accept(text);
        }, this::reopen, this::onAbandon);
    }

    /** 等輸入時玩家離線。 */
    protected void onAbandon() {
    }

    public void reopen() {
        if (viewer.isOnline()) {
            open();
        }
    }

    protected String plain(String key, Map<String, String> vars) {
        return Texts.plain(plugin.messages().raw(key), vars);
    }
}
