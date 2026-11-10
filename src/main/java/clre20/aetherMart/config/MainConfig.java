package clre20.aetherMart.config;

import clre20.aetherMart.util.TimeUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** config.yml：通用設定。 */
public final class MainConfig {

    public record CurrencyDef(String id, String type, String providerId, String name, String unit, boolean isDefault) {
    }

    public String prefix;
    public String language;
    public ZoneId zone;
    public BigDecimal maxAmount;
    public final Map<String, CurrencyDef> currencies = new LinkedHashMap<>();
    public int chatTimeout;
    public String cancelWord;
    public boolean bedrockFixedTitle;

    public String token;
    public String adminChannel;
    public boolean adminPerTrade;
    public boolean dailyEnabled;
    public int[] dailyTime;
    public boolean dailySendEmpty;
    public boolean personalEnabled;
    public boolean requireLinkBuy;
    public boolean requireLinkSell;
    public boolean allowBypass;
    public long sendIntervalMs;
    public int peakThreshold;
    public long mergeWindowMs;
    public int linkRefreshSeconds;

    public long logMaxFileBytes;
    public int logRetentionDays;
    public long logMaxTotalBytes;
    public int reportRetentionDays;
    public int recordRetentionDays;
    public boolean sounds;
    public boolean updateOnStartup;
    public boolean updateNotifyJoin;

    public void load(JavaPlugin plugin) {
        YamlConfiguration c = YamlFiles.load(plugin, new File(plugin.getDataFolder(), "config.yml"), "config.yml", Set.of("currencies"));
        prefix = c.getString("prefix", "&8[&bAetherMart&8] &r");
        String lang = c.getString("language", "zh_tw").toLowerCase(Locale.ROOT);
        language = switch (lang) {
            case "zh_cn", "en_us" -> lang;
            default -> "zh_tw";
        };
        try {
            zone = ZoneId.of(c.getString("timezone", "Asia/Taipei"));
        } catch (DateTimeException e) {
            plugin.getLogger().warning("Invalid timezone, using Asia/Taipei");
            zone = ZoneId.of("Asia/Taipei");
        }
        maxAmount = positive(c.getString("max-amount", "1000000000"), new BigDecimal("1000000000"));

        currencies.clear();
        ConfigurationSection cs = c.getConfigurationSection("currencies");
        boolean hasDefault = false;
        if (cs != null) {
            for (String id : cs.getKeys(false)) {
                if (!id.matches("[a-z0-9_-]{1,32}")) {
                    plugin.getLogger().warning("Currency id '" + id + "' is invalid (use a-z 0-9 _ -), skipped");
                    continue;
                }
                ConfigurationSection s = cs.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                String type = s.getString("type", "vault").toLowerCase(Locale.ROOT);
                if (!type.equals("vault") && !type.equals("excellenteconomy")) {
                    plugin.getLogger().warning("Currency '" + id + "' has unknown type " + type + ", skipped");
                    continue;
                }
                boolean def = s.getBoolean("default", false) && !hasDefault;
                hasDefault |= def;
                currencies.put(id, new CurrencyDef(id, type, s.getString("id", ""),
                        s.getString("name", id), s.getString("unit", id), def));
            }
        }
        if (currencies.isEmpty()) {
            currencies.put("money", new CurrencyDef("money", "vault", "", "Money", "$", true));
        } else if (!hasDefault) {
            CurrencyDef first = currencies.values().iterator().next();
            currencies.put(first.id(), new CurrencyDef(first.id(), first.type(), first.providerId(), first.name(), first.unit(), true));
        }

        chatTimeout = clamp(c.getInt("chat-input.timeout", 60), 10, 600);
        cancelWord = c.getString("chat-input.cancel-word", "取消");
        bedrockFixedTitle = c.getBoolean("bedrock.fixed-title", true);

        token = c.getString("discord.token", "").trim();
        adminChannel = c.getString("discord.admin.channel-id", "").trim();
        adminPerTrade = c.getBoolean("discord.admin.per-trade", true);
        dailyEnabled = c.getBoolean("discord.admin.daily-summary.enabled", true);
        dailyTime = TimeUtil.parseClock(c.get("discord.admin.daily-summary.time", "00:00"));
        if (dailyTime == null) {
            dailyTime = new int[]{0, 0};
        }
        dailySendEmpty = c.getBoolean("discord.admin.daily-summary.send-when-empty", true);
        personalEnabled = c.getBoolean("discord.personal.enabled", true);
        requireLinkBuy = c.getBoolean("discord.require-link.buy", false);
        requireLinkSell = c.getBoolean("discord.require-link.sell", false);
        allowBypass = c.getBoolean("discord.require-link.allow-bypass", true);
        sendIntervalMs = clamp(c.getInt("discord.send-interval-ms", 1500), 250, 60_000);
        peakThreshold = clamp(c.getInt("discord.peak-threshold", 20), 2, 10_000);
        mergeWindowMs = clamp(c.getInt("discord.merge-window-minutes", 5), 1, 120) * 60_000L;
        linkRefreshSeconds = clamp(c.getInt("discord.link-refresh-seconds", 60), 10, 3600);

        logMaxFileBytes = clamp(c.getInt("logs.max-file-size-mb", 10), 1, 1024) * 1024L * 1024L;
        logRetentionDays = clamp(c.getInt("logs.retention-days", 90), 0, 36500);
        logMaxTotalBytes = clamp(c.getInt("logs.max-total-mb", 500), 0, 1_000_000) * 1024L * 1024L;
        reportRetentionDays = clamp(c.getInt("reports.retention-days", 90), 0, 36500);
        recordRetentionDays = clamp(c.getInt("records.retention-days", 30), 1, 3650);
        sounds = c.getBoolean("sounds.enabled", true);
        updateOnStartup = c.getBoolean("update-checker.check-on-startup", true);
        updateNotifyJoin = c.getBoolean("update-checker.notify-on-join", true);
    }

    public CurrencyDef defaultCurrency() {
        for (CurrencyDef d : currencies.values()) {
            if (d.isDefault()) {
                return d;
            }
        }
        return currencies.values().iterator().next();
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    static BigDecimal positive(String s, BigDecimal fallback) {
        try {
            BigDecimal b = new BigDecimal(s.trim());
            return b.signum() > 0 ? b : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
