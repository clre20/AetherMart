package clre20.aetherMart.report;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.config.PdfConfig;
import clre20.aetherMart.currency.Currency;
import clre20.aetherMart.data.TradeRecord;
import clre20.aetherMart.pdf.DailySummary;
import clre20.aetherMart.pdf.Receipt;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 每日彙整：到 config 的結算時間，把「上次結算到現在」的交易做成 PDF，存在 reports/ 並送到管理員頻道。
 * 關服時錯過的結算時間，下次啟動補做。同時負責定期清除過期資料。
 */
public final class DailyReport {

    private static final String LAST = "daily.last";

    private final AetherMart plugin;
    private boolean running;
    private long lastMaintenance;

    public DailyReport(AetherMart plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::check, 100L, 600L);
    }

    private long boundaryAtOrBefore(long now) {
        ZoneId zone = plugin.config().zone;
        int[] t = plugin.config().dailyTime;
        ZonedDateTime n = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone);
        ZonedDateTime b = n.toLocalDate().atTime(LocalTime.of(t[0], t[1])).atZone(zone);
        if (b.isAfter(n)) {
            b = b.minusDays(1);
        }
        return b.toInstant().toEpochMilli();
    }

    private void check() {
        long now = System.currentTimeMillis();
        if (now - lastMaintenance > 3_600_000L) {
            lastMaintenance = now;
            maintenance(now);
        }
        if (!plugin.config().dailyEnabled || running) {
            return;
        }
        long boundary = boundaryAtOrBefore(now);
        long last = plugin.meta().getLong(LAST, 0);
        if (last == 0) {
            plugin.meta().set(LAST, String.valueOf(boundary));
            return;
        }
        if (boundary <= last) {
            return;
        }
        running = true;
        long from = last;
        plugin.trades().between(from, boundary).whenComplete((list, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                running = false;
                return;
            }
            try {
                produce(list, from, boundary);
            } finally {
                plugin.meta().set(LAST, String.valueOf(boundary));
                running = false;
            }
        }));
    }

    private void maintenance(long now) {
        plugin.trades().deleteOlderThan(now - plugin.config().recordRetentionDays * 86_400_000L);
        plugin.players().cleanupPurchases(plugin.shop().currentPeriods(now));
        int days = plugin.config().reportRetentionDays;
        if (days > 0) {
            long cutoff = now - days * 86_400_000L;
            File dir = new File(plugin.getDataFolder(), "reports");
            plugin.worker().execute(() -> {
                if (!dir.isDirectory()) {
                    return;
                }
                try (Stream<Path> s = Files.list(dir.toPath())) {
                    for (Path p : s.toList()) {
                        if (p.getFileName().toString().endsWith(".pdf") && p.toFile().lastModified() < cutoff) {
                            Files.deleteIfExists(p);
                        }
                    }
                } catch (IOException ignored) {
                }
            });
        }
    }

    private String type(PdfConfig pdf, TradeRecord.Type t) {
        String key = switch (t) {
            case SHOP_BUY -> "shop-buy";
            case LIST -> "list";
            case BID -> "bid";
            case SALE -> "sale";
            case SALE_BID -> "sale-bid";
            case CANCEL -> "cancel";
            case FORCE_REMOVE -> "force-remove";
            case EXPIRE -> "expire";
            case PENALTY -> "penalty";
        };
        return pdf.text("text.trade-types." + key);
    }

    private String money(String currency, String amount) {
        Currency c = plugin.currencies().get(currency);
        BigDecimal v;
        try {
            v = new BigDecimal(amount);
        } catch (RuntimeException e) {
            return amount == null ? "" : amount;
        }
        return c == null ? v.toPlainString() + " " + currency : c.format(v);
    }

    private String curName(String id) {
        Currency c = plugin.currencies().get(id);
        return c == null ? id : c.name();
    }

    /** 在主執行緒算好內容，交給背景執行緒畫 PDF、存檔、排入 Discord。 */
    private void produce(List<TradeRecord> list, long from, long to) {
        PdfConfig pdf = plugin.pdf();
        ZoneId zone = plugin.config().zone;
        String none = pdf.text("text.values.none");
        DailySummary s = new DailySummary();
        String day = TimeUtil.format(to, zone, DateTimeFormatter.ofPattern("yyyyMMdd"));
        s.number = "AM-D-" + day;
        s.settledAt = TimeUtil.format(to, zone, TimeUtil.DATE_TIME_SHORT);
        s.period = TimeUtil.format(from, zone, TimeUtil.DATE_TIME_SHORT) + " ～ " + TimeUtil.format(to, zone, TimeUtil.DATE_TIME_SHORT);
        s.timezone = zone.getId();
        s.stampDate = TimeUtil.format(to, zone, DateTimeFormatter.ofPattern("yyyy.MM.dd"));

        Map<String, BigDecimal[]> stats = new LinkedHashMap<>();
        Map<String, Integer> statCount = new LinkedHashMap<>();
        Set<UUID> people = new HashSet<>();
        List<String> curOrder = new ArrayList<>();
        for (Currency c : plugin.currencies().all()) {
            curOrder.add(c.id());
        }
        Map<TradeRecord.Type, Integer> typeCount = new LinkedHashMap<>();
        Map<TradeRecord.Type, Map<String, BigDecimal>> typeSum = new LinkedHashMap<>();
        Map<String, List<TradeRecord>> salesByCur = new LinkedHashMap<>();
        int sales = 0;
        for (TradeRecord r : list) {
            if (r.actor() != null) {
                people.add(r.actor());
            }
            if (r.other() != null) {
                people.add(r.other());
            }
            typeCount.merge(r.type(), 1, Integer::sum);
            BigDecimal amount = parse(r.amount());
            if (r.type().isSale() || r.type() == TradeRecord.Type.PENALTY) {
                typeSum.computeIfAbsent(r.type(), k -> new LinkedHashMap<>()).merge(r.currency(), amount, BigDecimal::add);
            }
            if (r.type().isSale()) {
                sales++;
                BigDecimal[] st = stats.computeIfAbsent(r.currency(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                st[0] = st[0].add(amount);
                st[1] = st[1].add(parse(r.tax()));
                statCount.merge(r.currency(), 1, Integer::sum);
                salesByCur.computeIfAbsent(r.currency(), k -> new ArrayList<>()).add(r);
            }
            if (r.type() != TradeRecord.Type.BID) {
                s.all.add(row(pdf, r, zone));
            }
        }
        s.tradeCount = sales;
        s.players = people.size();
        List<String> usedCur = new ArrayList<>();
        for (String id : curOrder) {
            if (stats.containsKey(id)) {
                usedCur.add(id);
            }
        }
        for (String id : stats.keySet()) {
            if (!usedCur.contains(id)) {
                usedCur.add(id);
            }
        }
        for (String id : usedCur) {
            BigDecimal[] st = stats.get(id);
            s.stats.add(new DailySummary.Stat(curName(id), money(id, st[0].toPlainString()), money(id, st[1].toPlainString()), statCount.get(id)));
        }
        List<String> tableCur = usedCur.isEmpty() ? List.of(plugin.currencies().getDefault().id()) : usedCur;
        for (String id : tableCur) {
            s.currencies.add(curName(id));
        }
        TradeRecord.Type[] order = {TradeRecord.Type.SHOP_BUY, TradeRecord.Type.SALE, TradeRecord.Type.SALE_BID, TradeRecord.Type.PENALTY,
                TradeRecord.Type.EXPIRE, TradeRecord.Type.CANCEL, TradeRecord.Type.FORCE_REMOVE, TradeRecord.Type.LIST, TradeRecord.Type.BID};
        for (TradeRecord.Type t : order) {
            Integer n = typeCount.get(t);
            if (n == null) {
                continue;
            }
            List<String> amounts = new ArrayList<>();
            Map<String, BigDecimal> sums = typeSum.get(t);
            for (String id : tableCur) {
                BigDecimal v = sums == null ? null : sums.get(id);
                amounts.add(v == null ? none : money(id, v.toPlainString()));
            }
            s.byType.add(new DailySummary.TypeRow(type(pdf, t), n, amounts));
        }
        for (String id : usedCur) {
            List<TradeRecord> rs = new ArrayList<>(salesByCur.get(id));
            rs.sort(Comparator.comparing((TradeRecord r) -> parse(r.amount())).reversed());
            List<DailySummary.Row> top = new ArrayList<>();
            for (int i = 0; i < Math.min(10, rs.size()); i++) {
                top.add(row(pdf, rs.get(i), zone));
            }
            s.topTitles.add(pdf.text("text.sections.top") + "（" + curName(id) + "）");
            s.tops.add(top);
        }
        for (String id : usedCur) {
            BigDecimal[] st = stats.get(id);
            s.totals.add(new Receipt.Total(pdf.text("text.totals.total-sales").replace("{currency}", curName(id)), money(id, st[0].toPlainString()), null));
            s.totals.add(new Receipt.Total(pdf.text("text.totals.total-tax").replace("{currency}", curName(id)), money(id, st[1].toPlainString()), null));
        }
        s.totals.add(new Receipt.Total(pdf.text("text.totals.trade-count"), pdf.text("text.values.records").replace("{count}", String.valueOf(sales)), "total"));

        StringBuilder overview = new StringBuilder();
        for (DailySummary.Stat st : s.stats) {
            overview.append(plugin.messages().plain("discord.daily.line", Vars.of("currency", st.currency(), "total", st.total(),
                    "tax", st.tax(), "count", st.count()))).append('\n');
        }
        Map<String, String> vars = Vars.of("period", s.period, "count", sales, "players", s.players, "lines", overview.toString().trim());
        boolean empty = list.isEmpty();
        int salesCount = sales;
        int recordCount = list.size();
        String generated = TimeUtil.format(System.currentTimeMillis(), zone, TimeUtil.DATE_TIME) + "（" + zone.getId() + "）";
        File dir = new File(plugin.getDataFolder(), "reports");
        plugin.worker().execute(() -> {
            String name = null;
            try {
                byte[] data = plugin.renderer().daily(s, generated);
                Files.createDirectories(dir.toPath());
                name = "daily-" + day + ".pdf";
                if (new File(dir, name).exists()) {
                    name = "daily-" + day + "-" + TimeUtil.format(to, zone, DateTimeFormatter.ofPattern("HHmm")) + ".pdf";
                }
                Files.write(new File(dir, name).toPath(), data);
                plugin.activity().log("REPORT", "daily report " + name + " (" + salesCount + " sales, " + recordCount + " records)");
            } catch (Exception e) {
                plugin.getLogger().warning("Cannot create daily report: " + e);
                plugin.activity().log("ERROR", "daily report failed: " + e);
            }
            if (empty && !plugin.config().dailySendEmpty) {
                return;
            }
            plugin.notifier().daily(empty ? null : name, vars, empty);
        });
    }

    private DailySummary.Row row(PdfConfig pdf, TradeRecord r, ZoneId zone) {
        String buyer = r.type() == TradeRecord.Type.SHOP_BUY || r.type() == TradeRecord.Type.SALE || r.type() == TradeRecord.Type.SALE_BID
                || r.type() == TradeRecord.Type.BID || r.type() == TradeRecord.Type.PENALTY ? nz(r.actorName()) : "";
        String seller = switch (r.type()) {
            case SHOP_BUY -> pdf.text("text.values.system-shop");
            case SALE, SALE_BID, BID, PENALTY -> nz(r.otherName());
            default -> nz(r.actorName());
        };
        return new DailySummary.Row(TimeUtil.format(r.time(), zone, DateTimeFormatter.ofPattern("HH:mm")), type(pdf, r.type()),
                buyer, seller, nz(r.summary()), money(r.currency(), r.amount()));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static BigDecimal parse(String s) {
        try {
            return new BigDecimal(s);
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }
}
