package clre20.aetherMart.discord;

import clre20.aetherMart.pdf.Receipt;

import java.util.LinkedHashMap;
import java.util.Map;

/** 通知佇列的一筆（存成 JSON，重啟不遺失）。 */
public final class Notice {

    public static final String PERSONAL = "personal";
    public static final String ADMIN = "admin";
    public static final String DAILY = "daily";

    public transient volatile long id;
    public transient volatile long retryAt;
    public String target;
    public String player;
    public String playerName;
    public int flag;
    public String key;
    public Map<String, String> vars = new LinkedHashMap<>();
    public Receipt receipt;
    /** 每日彙整的 PDF 檔名（reports/ 底下）。 */
    public String file;
    public long created;
    public int attempts;

    /** 尖峰合併時的分組鍵。 */
    String groupKey() {
        return PERSONAL.equals(target) ? "p:" + player : target;
    }
}
