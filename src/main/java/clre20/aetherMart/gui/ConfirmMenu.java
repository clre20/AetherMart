package clre20.aetherMart.gui;

import clre20.aetherMart.AetherMart;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/** 確認頁：11 確認｜13 資訊｜15 取消。 */
public final class ConfirmMenu extends Menu {

    private final String base;
    private final Map<String, String> vars;
    private final ItemStack info;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    public ConfirmMenu(AetherMart plugin, Player viewer, String base, Map<String, String> vars, ItemStack info,
                       Runnable onConfirm, Runnable onCancel) {
        super(plugin, viewer);
        this.base = base;
        this.vars = vars;
        this.info = info;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    @Override
    public String getMenuId() {
        return "confirm";
    }

    @Override
    protected String titleKey() {
        return base + ".title";
    }

    @Override
    protected Map<String, String> titleVars() {
        return vars;
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
        set(11, plugin.icons().of(Material.LIME_CONCRETE, base + ".confirm", vars), e -> onConfirm.run());
        if (info != null) {
            set(13, info.clone());
        }
        set(15, plugin.icons().of(Material.RED_CONCRETE, base + ".cancel", vars), e -> onCancel.run());
    }
}
