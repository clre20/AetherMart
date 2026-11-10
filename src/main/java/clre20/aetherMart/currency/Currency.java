package clre20.aetherMart.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** 一種幣別：名稱、單位、來源。金額一律用 BigDecimal。 */
public final class Currency {

    private final String id;
    private final String name;
    private final String unit;
    private final boolean isDefault;
    private final CurrencyProvider provider;

    public Currency(String id, String name, String unit, boolean isDefault, CurrencyProvider provider) {
        this.id = id;
        this.name = name;
        this.unit = unit;
        this.isDefault = isDefault;
        this.provider = provider;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String unit() {
        return unit;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public CurrencyProvider provider() {
        return provider;
    }

    public boolean available() {
        return provider.available();
    }

    public int decimals() {
        return provider.decimals();
    }

    public BigDecimal round(BigDecimal v) {
        return v.setScale(decimals(), RoundingMode.HALF_UP);
    }

    public BigDecimal ceil(BigDecimal v) {
        return v.setScale(decimals(), RoundingMode.CEILING);
    }

    /** 小數位數是否在這個幣別允許的範圍內。 */
    public boolean validScale(BigDecimal v) {
        return v.stripTrailingZeros().scale() <= decimals();
    }

    /** 金額文字，例如「1,234.50 元」。 */
    public String format(BigDecimal v) {
        return number(v) + " " + unit;
    }

    public String number(BigDecimal v) {
        int d = decimals();
        DecimalFormat f = new DecimalFormat(d > 0 ? "#,##0." + "0".repeat(d) : "#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(v);
    }

    /** 稅額：金額 × 稅率 %，依幣別小數位數四捨五入。 */
    public BigDecimal tax(BigDecimal amount, BigDecimal percent) {
        if (percent.signum() <= 0) {
            return BigDecimal.ZERO.setScale(decimals(), RoundingMode.HALF_UP);
        }
        return round(amount.multiply(percent).divide(BigDecimal.valueOf(100)));
    }
}
