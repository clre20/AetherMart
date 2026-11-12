package clre20.aetherMart.pdf;

import clre20.aetherMart.config.PdfConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 產生 PDF：個人明細、尖峰合併明細、每日彙整。
 * 版面：主題色頁首（伺服器名稱＋標題）、雙欄資料區、物品表、金額框、蓋上去的彩色圓形印章、備註區、頁尾頁碼。
 * 同一時間只畫一份（synchronized），字型閒置 5 分鐘後釋放。
 */
public final class PdfRenderer {

    private static final float M = 42;
    private static final Color INK = new Color(0x111827);
    private static final Color MUTED = new Color(0x6B7280);
    private static final Color LINE = new Color(0xE5E7EB);
    private static final Color SOFT = new Color(0xF3F7F8);
    private static final Color ZEBRA = new Color(0xF9FAFB);
    private static final Color HEAD = new Color(0xE3EEF0);
    private static final Color LIGHT = new Color(0xCFE3E6);
    private static final Color MINUS = new Color(0xB91C1C);

    /** 表格欄位。 */
    record Col(String title, float width, char align) {
    }

    /** 儲存格：主文字與（可選）下方小字。 */
    record Cell(String main, String sub) {
        static Cell of(String s) {
            return new Cell(s, null);
        }
    }

    private final JavaPlugin plugin;
    private final PdfFonts fonts;
    private final Supplier<PdfConfig> config;
    private final Supplier<String> language;

    public PdfRenderer(JavaPlugin plugin, Supplier<PdfConfig> config, Supplier<String> language) {
        this.plugin = plugin;
        this.fonts = new PdfFonts(plugin);
        this.config = config;
        this.language = language;
    }

    public synchronized void releaseIfIdle() {
        fonts.releaseIfIdle();
    }

    public synchronized void close() {
        fonts.release();
    }

    // =====================================================================

    /** 一份文件的版面狀態。 */
    private final class Doc {
        final PdfCanvas c;
        final PdfConfig cfg;
        final String generated;
        float y;
        String kind;

        Doc(PdfCanvas c, PdfConfig cfg, String generated) {
            this.c = c;
            this.cfg = cfg;
            this.generated = generated;
        }

        String tx(String path) {
            return cfg.text("text." + path);
        }

        void header(String kind, boolean first) throws IOException {
            this.kind = kind;
            PdfConfig.TypeStyle st = cfg.type(kind);
            float h = first ? 96 : 54;
            float top = PdfCanvas.H;
            c.fillRect(0, top - h, PdfCanvas.W, h, cfg.theme());
            c.fillRect(0, top - h - 5, PdfCanvas.W, 5, st.color());
            if (first) {
                c.text(c.fit(cfg.serverName(), true, 20, PdfCanvas.W / 2 - M), M, top - 46, true, 20, Color.WHITE);
                c.text(cfg.subtitle(), M, top - 64, false, 9, LIGHT);
                c.textRight(st.title(), PdfCanvas.W - M, top - 48, true, 22, Color.WHITE);
                c.textRight(st.subtitle(), PdfCanvas.W - M, top - 64, false, 9, LIGHT);
                y = top - h - 32;
            } else {
                c.text(c.fit(cfg.serverName(), true, 13, PdfCanvas.W / 2 - M), M, top - 33, true, 13, Color.WHITE);
                c.textRight(tx("continued").replace("{title}", st.title()), PdfCanvas.W - M, top - 33, false, 10, Color.WHITE);
                y = top - h - 26;
            }
        }

        void nextPage(String newKind) throws IOException {
            c.newPage();
            if (newKind != null) {
                header(newKind, true);
            } else {
                header(kind, false);
            }
        }

        void need(float height) throws IOException {
            if (y - height < 70) {
                nextPage(null);
            }
        }

        void info(List<Receipt.Field> fields) throws IOException {
            int n = (fields.size() + 1) / 2;
            float rh = 34;
            float h = n * rh + 16;
            float top = y;
            c.roundRect(M, top - h, PdfCanvas.W - 2 * M, h, 8, SOFT, LINE, 0.8f);
            float colw = (PdfCanvas.W - 2 * M) / 2;
            for (int i = 0; i < fields.size(); i++) {
                Receipt.Field f = fields.get(i);
                float x = M + 16 + (i % 2) * colw;
                float yy = top - 22 - (i / 2) * rh;
                c.text(c.fit(f.label, false, 8.5f, colw - 28), x, yy, false, 8.5f, MUTED);
                c.text(c.fit(f.value, f.bold, 11, colw - 28), x, yy - 15, f.bold, 11, INK);
            }
            y = top - h - 26;
        }

        void section(String title, float keep) throws IOException {
            need(keep);
            c.fillRect(M, y - 2, 3, 13, cfg.theme());
            c.text(title, M + 10, y, true, 12, INK);
            y -= 16;
        }

        void table(List<Col> cols, List<List<Cell>> rows, boolean twoLine) throws IOException {
            float rh = twoLine ? 30 : 20;
            need(20 + rh * (rows.size() <= 12 ? Math.max(1, rows.size()) : 1));
            tableHead(cols);
            for (int i = 0; i < rows.size(); i++) {
                if (y - rh < 70) {
                    nextPage(null);
                    tableHead(cols);
                }
                if (i % 2 == 1) {
                    c.fillRect(M, y - rh, PdfCanvas.W - 2 * M, rh, ZEBRA);
                }
                float x = M;
                List<Cell> r = rows.get(i);
                for (int k = 0; k < cols.size(); k++) {
                    Col col = cols.get(k);
                    Cell cell = k < r.size() ? r.get(k) : Cell.of("");
                    if (twoLine && cell.sub() != null) {
                        cell(cell.main(), x, y - 13, col, false, 10, INK);
                        cell(cell.sub(), x, y - 25, col, false, 7.5f, MUTED);
                    } else {
                        cell(cell.main(), x, y - (twoLine ? 18 : 14), col, false, twoLine ? 9.5f : 8.5f, INK);
                    }
                    x += col.width();
                }
                y -= rh;
                c.line(M, y, PdfCanvas.W - M, y, 0.5f, LINE);
            }
            y -= 22;
        }

        private void tableHead(List<Col> cols) throws IOException {
            c.fillRect(M, y - 20, PdfCanvas.W - 2 * M, 20, HEAD);
            float x = M;
            for (Col col : cols) {
                cell(col.title(), x, y - 14, col, true, 9, cfg.theme());
                x += col.width();
            }
            y -= 20;
        }

        private void cell(String text, float x, float yy, Col col, boolean isBold, float size, Color color) throws IOException {
            float pad = 8;
            String t = c.fit(text == null ? "" : text, isBold, size, col.width() - 2 * pad);
            switch (col.align()) {
                case 'r' -> c.textRight(t, x + col.width() - pad, yy, isBold, size, color);
                case 'c' -> c.textCenter(t, x + col.width() / 2, yy, isBold, size, color);
                default -> c.text(t, x + pad, yy, isBold, size, color);
            }
        }

        void amounts(List<Receipt.Total> totals, String stampDate) throws IOException {
            float bw = 250, rh = 22;
            float h = totals.size() * rh + 20;
            float block = Math.max(h, 118);
            need(block + 10);
            float top = y;
            float x = PdfCanvas.W - M - bw;
            c.roundRect(x, top - h, bw, h, 8, Color.WHITE, LINE, 0.8f);
            float yy = top - 22;
            for (Receipt.Total t : totals) {
                if ("total".equals(t.style)) {
                    c.line(x + 14, yy + 13, x + bw - 14, yy + 13, 1, cfg.theme());
                    yy -= 4;
                    c.text(c.fit(t.label, true, 10.5f, bw - 140), x + 14, yy, true, 10.5f, INK);
                    c.textRight(t.value, x + bw - 14, yy, true, 14, cfg.theme());
                } else {
                    c.text(c.fit(t.label, false, 9.5f, bw - 120), x + 14, yy, false, 9.5f, MUTED);
                    c.textRight(t.value, x + bw - 14, yy, false, 10.5f, "minus".equals(t.style) ? MINUS : INK);
                }
                yy -= rh;
            }
            stamp(M + 92, top - block / 2 - 2, stampDate);
            y = top - block - 24;
        }

        void notes(List<String> lines) throws IOException {
            if (lines == null || lines.isEmpty()) {
                return;
            }
            float h = 16 + 16 * lines.size();
            need(16 + h + 10);
            section(tx("sections.notes"), 0);
            float top = y - 2;
            c.roundRect(M, top - h, PdfCanvas.W - 2 * M, h, 8, SOFT, LINE, 0.8f);
            float yy = top - 19;
            for (String line : lines) {
                c.circle(M + 18, yy + 3, 1.8f, cfg.theme(), null, 0);
                c.text(c.fit(line, false, 9.5f, PdfCanvas.W - 2 * M - 44), M + 28, yy, false, 9.5f, INK);
                yy -= 16;
            }
            y = top - h - 22;
        }

        /** 蓋上去的圓形印章：雙圈、傾斜、半透明、墨色斑駁；上弧伺服器名稱、中間狀態、下弧日期。 */
        void stamp(float cx, float cy, String date) throws IOException {
            PdfConfig.TypeStyle st = cfg.type(kind);
            Color col = st.color();
            String word = st.stamp();
            float r = 50;
            Random rnd = new Random((kind + word).hashCode());
            double angle = Math.toRadians(-(9 + rnd.nextDouble() * 7));
            c.save();
            c.rotateAround(angle, cx, cy);
            c.alpha(0.82f);
            c.circle(cx, cy, r, null, col, 3.2f);
            c.circle(cx, cy, r - 6, null, col, 1f);
            for (float dy : new float[]{15, -15}) {
                float half = (float) Math.sqrt((r - 6) * (r - 6) - dy * dy);
                c.line(cx - half + 2, cy + dy, cx + half - 2, cy + dy, 0.9f, col);
            }
            float size = word.codePointCount(0, word.length()) <= 3 ? 21 : 17;
            c.text(word, cx - c.width(word, true, size) / 2, cy - size * 0.36f, true, size, col);
            arc(cfg.serverName(), cx, cy, r - 15, true, 8.5f, col);
            arc(date, cx, cy, r - 15, false, 8, col);
            c.alpha(0.75f);
            for (int i = 0; i < 140; i++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                double d = r * Math.sqrt(rnd.nextDouble()) * 1.05;
                c.circle((float) (cx + d * Math.cos(a)), (float) (cy + d * Math.sin(a)), (float) (0.3 + rnd.nextDouble()), Color.WHITE, null, 0);
            }
            c.alpha(0.9f);
            for (int i = 0; i < 6; i++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                c.circle((float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a)), (float) (1.5 + rnd.nextDouble() * 1.5), Color.WHITE, null, 0);
            }
            c.restore();
        }

        private void arc(String text, float cx, float cy, float radius, boolean top, float size, Color col) throws IOException {
            if (text == null || text.isEmpty()) {
                return;
            }
            List<String> chars = new ArrayList<>();
            text.codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));
            if (chars.size() > 16) {
                chars.subList(16, chars.size()).clear();
            }
            float[] widths = new float[chars.size()];
            float total = 0;
            for (int i = 0; i < chars.size(); i++) {
                widths[i] = c.width(chars.get(i), top, size) + 1.2f;
                total += widths[i];
            }
            double span = total / radius;
            double a = top ? Math.PI / 2 + span / 2 : 3 * Math.PI / 2 - span / 2;
            for (int i = 0; i < chars.size(); i++) {
                double step = widths[i] / radius;
                double mid = top ? a - step / 2 : a + step / 2;
                float x = (float) (cx + radius * Math.cos(mid));
                float yy = (float) (cy + radius * Math.sin(mid));
                double rot = top ? mid - Math.PI / 2 : mid + Math.PI / 2;
                c.rotatedCenter(chars.get(i), x, yy, rot, top, size, col);
                a = top ? a - step : a + step;
            }
        }

        void footers() throws IOException {
            int total = c.pages.size();
            for (int i = 0; i < total; i++) {
                PDPage p = c.pages.get(i);
                c.reopen(p);
                c.line(M, 48, PdfCanvas.W - M, 48, 0.8f, LINE);
                c.text(tx("generated").replace("{time}", generated), M, 34, false, 8, MUTED);
                c.textCenter(tx("footer"), PdfCanvas.W / 2, 22, false, 7.5f, MUTED);
                c.textRight(tx("page").replace("{page}", String.valueOf(i + 1)).replace("{pages}", String.valueOf(total)),
                        PdfCanvas.W - M, 34, false, 8, MUTED);
            }
        }
    }

    // =====================================================================

    private interface Body {
        void draw(Doc d) throws IOException;
    }

    private byte[] build(String title, String generated, Body body) throws IOException {
        PdfConfig cfg = config.get();
        fonts.ensure(language.get(), cfg.fontFile(false), cfg.fontFile(true));
        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(title);
            info.setAuthor("AetherMart");
            info.setCreator("AetherMart");
            info.setCreationDate(Calendar.getInstance());
            try (PdfCanvas c = new PdfCanvas(doc, fonts)) {
                Doc d = new Doc(c, cfg, generated);
                c.newPage();
                body.draw(d);
                d.footers();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            doc.save(out);
            return out.toByteArray();
        }
    }

    private List<Col> itemCols(Doc d) {
        List<Col> cols = new ArrayList<>();
        cols.add(new Col(d.tx("columns.index"), 34, 'c'));
        cols.add(new Col(d.tx("columns.item"), 220, 'l'));
        cols.add(new Col(d.tx("columns.amount"), 56, 'r'));
        float used = 34 + 220 + 56;
        cols.add(new Col(d.tx("columns.remark"), PdfCanvas.W - 2 * M - used, 'l'));
        return cols;
    }

    private void receiptBody(Doc d, Receipt r, boolean first) throws IOException {
        if (first) {
            d.header(r.kind, true);
        }
        d.info(r.fields);
        d.section(r.itemsTitle, 40);
        List<List<Cell>> rows = new ArrayList<>();
        for (int i = 0; i < r.lines.size(); i++) {
            Receipt.Line l = r.lines.get(i);
            rows.add(List.of(Cell.of(String.valueOf(i + 1)), new Cell(l.name, l.id), Cell.of(String.valueOf(l.amount)), Cell.of(l.remark)));
        }
        d.table(itemCols(d), rows, true);
        d.section(d.tx("sections.amount"), 150);
        d.y -= 4;
        d.amounts(r.totals, r.stampDate);
        d.notes(r.notes);
    }

    /** 一份個人明細。 */
    public synchronized byte[] receipt(Receipt r, String generated) throws IOException {
        return build(r.number, generated, d -> receiptBody(d, r, true));
    }

    /** 合併明細（尖峰合併、每日收據）：第一頁總表與各幣別合計，之後一筆一頁（最多 maxPages 頁）。 */
    public synchronized byte[] combined(List<Receipt> list, String number, String player, String period, String generated,
                                        String reasonKey, int maxPages) throws IOException {
        return build(number, generated, d -> {
            d.header("combined", true);
            List<Receipt.Field> f = new ArrayList<>();
            f.add(new Receipt.Field(d.tx("fields.id"), number, true));
            f.add(new Receipt.Field(d.tx("fields.period"), period, false));
            f.add(new Receipt.Field(d.tx("fields.player"), player, false));
            f.add(new Receipt.Field(d.tx("fields.records"), d.tx("values.records").replace("{count}", String.valueOf(list.size())), false));
            f.add(new Receipt.Field(d.tx("fields.reason"), d.tx(reasonKey), false));
            int shown = Math.min(list.size(), Math.max(1, maxPages));
            f.add(new Receipt.Field(d.tx("fields.pages"), shown < list.size()
                    ? d.tx("values.first-pages").replace("{shown}", String.valueOf(shown)) : d.tx("values.each-page"), false));
            d.info(f);
            d.section(d.tx("sections.summary"), 40);
            List<Col> cols = new ArrayList<>();
            cols.add(new Col("#", 34, 'c'));
            cols.add(new Col(d.tx("columns.time"), 70, 'l'));
            cols.add(new Col(d.tx("columns.type"), 150, 'l'));
            cols.add(new Col(d.tx("columns.auction"), 80, 'l'));
            cols.add(new Col(d.tx("columns.money"), PdfCanvas.W - 2 * M - 334, 'r'));
            List<List<Cell>> rows = new ArrayList<>();
            java.util.Map<String, double[]> flows = new java.util.LinkedHashMap<>();
            java.util.Map<String, Receipt> sample = new java.util.HashMap<>();
            for (int i = 0; i < list.size(); i++) {
                Receipt r = list.get(i);
                rows.add(List.of(Cell.of(String.valueOf(i + 1)), Cell.of(r.summaryTime), Cell.of(r.summaryType),
                        Cell.of(r.summaryAuction), Cell.of(r.summaryAmount)));
                if (r.flowCurrency != null) {
                    double[] fl = flows.computeIfAbsent(r.flowCurrency, k -> new double[2]);
                    if (r.flowValue >= 0) {
                        fl[0] += r.flowValue;
                    } else {
                        fl[1] += -r.flowValue;
                    }
                    sample.putIfAbsent(r.flowCurrency, r);
                }
            }
            d.table(cols, rows, false);
            if (!flows.isEmpty()) {
                d.section(d.tx("sections.by-currency"), 40);
                List<Col> fc = List.of(new Col(d.tx("columns.currency"), 150, 'l'), new Col(d.tx("columns.spent"), 140, 'r'),
                        new Col(d.tx("columns.received"), 120, 'r'), new Col(d.tx("columns.net"), PdfCanvas.W - 2 * M - 410, 'r'));
                List<List<Cell>> fr = new ArrayList<>();
                for (java.util.Map.Entry<String, double[]> e : flows.entrySet()) {
                    String unit = unitOf(sample.get(e.getKey()));
                    double net = e.getValue()[1] - e.getValue()[0];
                    fr.add(List.of(Cell.of(e.getKey()), Cell.of(num(e.getValue()[0]) + unit), Cell.of(num(e.getValue()[1]) + unit),
                            Cell.of((net < 0 ? "−" : "") + num(Math.abs(net)) + unit)));
                }
                d.table(fc, fr, false);
            }
            for (int i = 0; i < shown; i++) {
                Receipt r = list.get(i);
                d.nextPage(r.kind);
                receiptBody(d, r, false);
            }
        });
    }

    private static String unitOf(Receipt r) {
        if (r == null || r.flowAmount == null) {
            return "";
        }
        int sp = r.flowAmount.lastIndexOf(' ');
        return sp < 0 ? "" : r.flowAmount.substring(sp);
    }

    private static String num(double v) {
        java.text.DecimalFormat f = new java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT));
        return f.format(v);
    }

    /** 每日彙整。 */
    public synchronized byte[] daily(DailySummary s, String generated) throws IOException {
        return build(s.number, generated, d -> {
            d.header("daily", true);
            List<Receipt.Field> f = new ArrayList<>();
            f.add(new Receipt.Field(d.tx("fields.id"), s.number, true));
            f.add(new Receipt.Field(d.tx("fields.settled-at"), s.settledAt, false));
            f.add(new Receipt.Field(d.tx("fields.period"), s.period, false));
            f.add(new Receipt.Field(d.tx("fields.timezone"), s.timezone, false));
            f.add(new Receipt.Field(d.tx("fields.trade-count"), d.tx("values.records").replace("{count}", String.valueOf(s.tradeCount)), false));
            f.add(new Receipt.Field(d.tx("fields.players"), d.tx("values.people").replace("{count}", String.valueOf(s.players)), false));
            d.info(f);
            if (!s.stats.isEmpty()) {
                d.section(d.tx("sections.overview"), 130);
                float cw = (PdfCanvas.W - 2 * M - 14) / 2;
                for (int i = 0; i < s.stats.size(); i++) {
                    if (i % 2 == 0 && i > 0) {
                        d.y -= 100;
                        d.need(100);
                    }
                    DailySummary.Stat st = s.stats.get(i);
                    float x = M + (i % 2) * (cw + 14);
                    float top = d.y - 4;
                    d.c.roundRect(x, top - 86, cw, 86, 8, Color.WHITE, LINE, 0.8f);
                    d.c.fillRect(x, top - 86, 4, 86, d.cfg.theme());
                    d.c.text(st.currency(), x + 16, top - 20, false, 9, MUTED);
                    d.c.text(d.c.fit(st.total(), true, 20, cw - 32), x + 16, top - 46, true, 20, INK);
                    d.c.text(d.c.fit(d.tx("totals.total-tax").replace("{currency}", st.currency()) + " " + st.tax() + "　·　"
                            + d.tx("values.records").replace("{count}", String.valueOf(st.count())), false, 8.5f, cw - 32), x + 16, top - 68, false, 8.5f, MUTED);
                }
                d.y = d.y - 4 - 86 - 26;
            }
            if (!s.byType.isEmpty()) {
                d.section(d.tx("sections.by-type"), 60);
                List<Col> cols = new ArrayList<>();
                cols.add(new Col(d.tx("columns.type"), 170, 'l'));
                cols.add(new Col(d.tx("columns.count"), 60, 'r'));
                float rest = PdfCanvas.W - 2 * M - 230;
                int n = Math.max(1, s.currencies.size());
                for (String cur : s.currencies) {
                    cols.add(new Col(cur, rest / n, 'r'));
                }
                List<List<Cell>> rows = new ArrayList<>();
                for (DailySummary.TypeRow tr : s.byType) {
                    List<Cell> r = new ArrayList<>();
                    r.add(Cell.of(tr.type()));
                    r.add(Cell.of(String.valueOf(tr.count())));
                    for (String a : tr.amounts()) {
                        r.add(Cell.of(a));
                    }
                    rows.add(r);
                }
                d.table(cols, rows, false);
            }
            List<Col> cols = new ArrayList<>();
            cols.add(new Col(d.tx("columns.time"), 54, 'l'));
            cols.add(new Col(d.tx("columns.type"), 92, 'l'));
            cols.add(new Col(d.tx("columns.buyer"), 78, 'l'));
            cols.add(new Col(d.tx("columns.seller"), 78, 'l'));
            cols.add(new Col(d.tx("columns.money"), 100, 'r'));
            float used = 54 + 92 + 78 + 78 + 100;
            cols.add(4, new Col(d.tx("columns.goods"), PdfCanvas.W - 2 * M - used, 'l'));
            for (int i = 0; i < s.tops.size(); i++) {
                List<DailySummary.Row> top = s.tops.get(i);
                if (top.isEmpty()) {
                    continue;
                }
                d.section(s.topTitles.get(i), 16 + 20 + 20 * top.size());
                d.table(cols, rows(top), false);
            }
            if (s.all.isEmpty()) {
                d.notes(List.of(d.tx("notes.daily-empty")));
            } else {
                d.section(d.tx("sections.all"), 80);
                d.table(cols, rows(s.all), false);
            }
            if (!s.totals.isEmpty()) {
                d.section(d.tx("sections.amount"), 150);
                d.y -= 4;
                d.amounts(s.totals, s.stampDate);
            }
        });
    }

    private static List<List<Cell>> rows(List<DailySummary.Row> list) {
        List<List<Cell>> out = new ArrayList<>(list.size());
        for (DailySummary.Row r : list) {
            out.add(List.of(Cell.of(r.time()), Cell.of(r.type()), Cell.of(r.buyer()), Cell.of(r.seller()), Cell.of(r.goods()), Cell.of(r.amount())));
        }
        return out;
    }

    JavaPlugin plugin() {
        return plugin;
    }
}
