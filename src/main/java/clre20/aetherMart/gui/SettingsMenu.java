package clre20.aetherMart.gui;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.player.PlayerData;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Map;

/** 個人設定：每種 Discord 私訊通知的開關、綁定狀態、受罰狀態。 */
public final class SettingsMenu extends Menu {

    private static final String[] KEYS = {"purchase", "won", "sold", "listed", "removed", "expired", "outbid"};
    private static final Material[] ICONS = {Material.EMERALD, Material.GOLD_BLOCK, Material.GOLD_NUGGET, Material.WRITABLE_BOOK,
            Material.HOPPER, Material.CLOCK, Material.ARROW};

    public SettingsMenu(AetherMart plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    public String getMenuId() {
        return "settings";
    }

    @Override
    protected String titleKey() {
        return "gui.settings.title";
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
        PlayerData d = plugin.players().get(viewer.getUniqueId());
        String link;
        if (!plugin.bot().configured() || !plugin.config().personalEnabled) {
            link = plain("gui.settings.link.disabled", Map.of());
        } else if (!plugin.links().available()) {
            link = plain("gui.settings.link.unavailable", Map.of());
        } else if (plugin.links().isLinked(viewer.getUniqueId())) {
            link = plain("gui.settings.link.linked", Map.of());
        } else {
            link = plain("gui.settings.link.not-linked", Map.of());
        }
        long now = System.currentTimeMillis();
        String penalty = d == null ? plain("common.loading-short", Map.of())
                : d.banned(now) ? plain("gui.settings.penalty.banned", Vars.of("until",
                TimeUtil.format(d.bannedUntil, plugin.config().zone, TimeUtil.DATE_TIME_SHORT)))
                : plain("gui.settings.penalty.none", Map.of());
        String debt = d == null ? "" : plugin.guard().debtText(d);
        set(4, plugin.icons().of(Material.NAME_TAG, "gui.settings.status", Vars.of("player", viewer.getName(), "link", link,
                "penalty", penalty, "debt", debt)));
        for (int i = 0; i < KEYS.length; i++) {
            int flag = PlayerData.ALL[i];
            boolean on = d != null && d.notifyEnabled(flag);
            String state = plain(on ? "gui.settings.on" : "gui.settings.off", Map.of());
            set(10 + i, plugin.icons().glow(plugin.icons().of(on ? ICONS[i] : Material.GRAY_DYE, "gui.settings.notify." + KEYS[i],
                    Vars.of("state", state)), on), e -> {
                PlayerData pd = plugin.players().get(viewer.getUniqueId());
                if (pd == null) {
                    plugin.messages().send(viewer, "common.loading");
                    return;
                }
                plugin.players().setNotify(viewer.getUniqueId(), flag, !pd.notifyEnabled(flag));
                refresh();
            });
        }
        set(22, plugin.icons().of(Material.ARROW, "gui.common.back"), e -> new HubMenu(plugin, viewer).open());
    }
}
