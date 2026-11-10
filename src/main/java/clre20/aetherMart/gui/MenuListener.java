package clre20.aetherMart.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/** 把點擊、拖曳、關閉事件交給對應的介面。不是 AetherMart 介面時第一行就返回。 */
public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder(false);
        if (!(h instanceof Menu m)) {
            return;
        }
        if (!(e.getWhoClicked() instanceof Player p) || !p.equals(m.viewer())) {
            e.setCancelled(true);
            return;
        }
        m.onClick(e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder(false);
        if (h instanceof Menu m) {
            m.onDrag(e);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder(false);
        if (h instanceof Menu m && !m.suspended) {
            m.onClose();
        }
    }

    /** 插件關閉時把開著的介面全部關掉（觸發收尾，例如退回建立拍賣的物品）。 */
    public static void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder(false);
            if (h instanceof Menu) {
                p.closeInventory();
            }
        }
    }
}
