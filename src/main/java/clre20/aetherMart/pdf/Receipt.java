package clre20.aetherMart.pdf;

import java.util.ArrayList;
import java.util.List;

/** 一份明細的內容（文字都已依 pdf.yml 填好），可轉成 JSON 存進通知佇列。 */
public final class Receipt {

    public static final class Field {
        public String label;
        public String value;
        public boolean bold;

        public Field() {
        }

        public Field(String label, String value, boolean bold) {
            this.label = label;
            this.value = value;
            this.bold = bold;
        }
    }

    public static final class Line {
        public String name;
        public String id;
        public int amount;
        public String remark;

        public Line() {
        }

        public Line(String name, String id, int amount, String remark) {
            this.name = name;
            this.id = id;
            this.amount = amount;
            this.remark = remark;
        }
    }

    public static final class Total {
        public String label;
        public String value;
        /** null、minus、total */
        public String style;

        public Total() {
        }

        public Total(String label, String value, String style) {
            this.label = label;
            this.value = value;
            this.style = style;
        }
    }

    /** pdf.yml text.types 的鍵，例如 shop-purchase。 */
    public String kind;
    public String number;
    public long time;
    public String stampDate;
    public String itemsTitle;
    public final List<Field> fields = new ArrayList<>();
    public final List<Line> lines = new ArrayList<>();
    public final List<Total> totals = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
    /** 合併總表用：時間、類型、拍賣編號、金額。 */
    public String summaryTime;
    public String summaryType;
    public String summaryAuction;
    public String summaryAmount;
    /** 合併時各幣別合計用：幣別名稱、支出（正）或收入（負）的金額文字與數值。 */
    public String flowCurrency;
    public String flowAmount;
    public double flowValue;
}
