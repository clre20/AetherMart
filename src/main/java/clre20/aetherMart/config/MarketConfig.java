package clre20.aetherMart.config;

import clre20.aetherMart.util.TimeUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** shop.yml 與 auction.yml。 */
public final class MarketConfig {

    // ---- shop.yml ----
    public final Map<String, BigDecimal> shopTax = new HashMap<>();
    public final Map<String, BigDecimal> shopMinPrice = new HashMap<>();
    public int[] shopDailyReset;
    public DayOfWeek shopWeeklyDay;
    public final List<Integer> quantityButtons = new ArrayList<>();

    // ---- auction.yml ----
    public final Set<String> auctionCurrencies = new LinkedHashSet<>();
    public final Map<String, BigDecimal> auctionTax = new HashMap<>();
    public final Map<String, BigDecimal> auctionMinPrice = new HashMap<>();
    public long durationMin;
    public long durationMax;
    public long durationDefault;
    public int defaultListingLimit;
    public BigDecimal minRaisePercent;
    public long knockIntervalMs;
    public int knockCount;
    public int penaltyBanDays;
    public BigDecimal penaltyFinePercent;

    public void load(JavaPlugin plugin) {
        YamlConfiguration s = YamlFiles.load(plugin, new File(plugin.getDataFolder(), "shop.yml"), "shop.yml", Set.of("tax", "min-price"));
        readMap(s.getConfigurationSection("tax"), shopTax, BigDecimal.ZERO, true);
        readMap(s.getConfigurationSection("min-price"), shopMinPrice, BigDecimal.ONE, false);
        shopDailyReset = TimeUtil.parseClock(s.get("limit-reset.daily-time", "00:00"));
        if (shopDailyReset == null) {
            shopDailyReset = new int[]{0, 0};
        }
        try {
            shopWeeklyDay = DayOfWeek.valueOf(s.getString("limit-reset.weekly-day", "MONDAY").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            shopWeeklyDay = DayOfWeek.MONDAY;
        }
        quantityButtons.clear();
        for (Object o : s.getList("quantity-buttons", List.of(1, 2, 4, 8, 16, 32))) {
            try {
                int n = Integer.parseInt(String.valueOf(o));
                if (n >= 1 && n <= 2304 && quantityButtons.size() < 7) {
                    quantityButtons.add(n);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (quantityButtons.isEmpty()) {
            quantityButtons.addAll(List.of(1, 2, 4, 8, 16, 32));
        }

        YamlConfiguration a = YamlFiles.load(plugin, new File(plugin.getDataFolder(), "auction.yml"), "auction.yml", Set.of("tax", "min-price"));
        auctionCurrencies.clear();
        for (String id : a.getStringList("allowed-currencies")) {
            auctionCurrencies.add(id.toLowerCase(Locale.ROOT));
        }
        readMap(a.getConfigurationSection("tax"), auctionTax, BigDecimal.ZERO, true);
        readMap(a.getConfigurationSection("min-price"), auctionMinPrice, BigDecimal.ONE, false);
        durationMin = dur(a.getString("duration.min", "1h"), 3_600_000L);
        durationMax = dur(a.getString("duration.max", "7d"), 7 * 86_400_000L);
        if (durationMax < durationMin) {
            durationMax = durationMin;
        }
        durationDefault = Math.max(durationMin, Math.min(durationMax, dur(a.getString("duration.default", "2d"), 2 * 86_400_000L)));
        defaultListingLimit = MainConfig.clamp(a.getInt("default-listing-limit", 3), 0, 1000);
        minRaisePercent = percent(a.getString("bidding.min-raise-percent", "5"), new BigDecimal("5"));
        knockIntervalMs = MainConfig.clamp(a.getInt("bidding.knock.interval-minutes", 5), 1, 1440) * 60_000L;
        knockCount = MainConfig.clamp(a.getInt("bidding.knock.count", 3), 1, 20);
        penaltyBanDays = MainConfig.clamp(a.getInt("penalty.ban-days", 7), 0, 36500);
        penaltyFinePercent = percent(a.getString("penalty.fine-percent", "10"), new BigDecimal("10"));
    }

    private static long dur(String s, long fallback) {
        long v = TimeUtil.parseDurationMillis(s);
        return v > 0 ? v : fallback;
    }

    private static BigDecimal percent(String s, BigDecimal fallback) {
        try {
            BigDecimal b = new BigDecimal(s.trim());
            return b.signum() < 0 || b.compareTo(new BigDecimal("1000")) > 0 ? fallback : b;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void readMap(ConfigurationSection sec, Map<String, BigDecimal> out, BigDecimal fallback, boolean isPercent) {
        out.clear();
        if (sec == null) {
            return;
        }
        for (String k : sec.getKeys(false)) {
            try {
                BigDecimal v = new BigDecimal(String.valueOf(sec.get(k)).trim());
                if (v.signum() < 0 || (isPercent && v.compareTo(new BigDecimal("100")) > 0)) {
                    v = fallback;
                }
                out.put(k.toLowerCase(Locale.ROOT), v);
            } catch (RuntimeException e) {
                out.put(k.toLowerCase(Locale.ROOT), fallback);
            }
        }
    }

    public BigDecimal shopTax(String currency) {
        return shopTax.getOrDefault(currency, BigDecimal.ZERO);
    }

    public BigDecimal shopMin(String currency) {
        return shopMinPrice.getOrDefault(currency, BigDecimal.ONE);
    }

    public BigDecimal auctionTax(String currency) {
        return auctionTax.getOrDefault(currency, BigDecimal.ZERO);
    }

    public BigDecimal auctionMin(String currency) {
        return auctionMinPrice.getOrDefault(currency, BigDecimal.ONE);
    }
}
