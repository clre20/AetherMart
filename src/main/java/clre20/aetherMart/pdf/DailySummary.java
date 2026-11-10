package clre20.aetherMart.pdf;

import java.util.ArrayList;
import java.util.List;

/** 每日彙整的內容（在主執行緒算好、文字已格式化），交給背景執行緒畫成 PDF。 */
public final class DailySummary {

    public record Stat(String currency, String total, String tax, int count) {
    }

    /** 依類型：類型名稱、筆數、各幣別金額（順序同 currencies）。 */
    public record TypeRow(String type, int count, List<String> amounts) {
    }

    public record Row(String time, String type, String buyer, String seller, String goods, String amount) {
    }

    public String number;
    public String settledAt;
    public String period;
    public String timezone;
    public int tradeCount;
    public int players;
    public String stampDate;
    public final List<String> currencies = new ArrayList<>();
    public final List<Stat> stats = new ArrayList<>();
    public final List<TypeRow> byType = new ArrayList<>();
    /** 每種幣別一段前 10 大：標題、列。 */
    public final List<String> topTitles = new ArrayList<>();
    public final List<List<Row>> tops = new ArrayList<>();
    public final List<Row> all = new ArrayList<>();
    /** 最後的金額框（各幣別成交總額、稅收、筆數）。 */
    public final List<Receipt.Total> totals = new ArrayList<>();
}
