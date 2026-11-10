package clre20.aetherMart.currency;

import clre20.aetherMart.config.MainConfig;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** config.yml 裡定義的所有幣別。 */
public final class CurrencyRegistry {

    private final Map<String, Currency> currencies = new LinkedHashMap<>();
    private final Logger logger;

    public CurrencyRegistry(Logger logger) {
        this.logger = logger;
    }

    public void load(MainConfig config) {
        currencies.clear();
        VaultProvider vault = new VaultProvider();
        for (MainConfig.CurrencyDef d : config.currencies.values()) {
            CurrencyProvider p;
            if (d.type().equals("excellenteconomy")) {
                ExcellentProvider ep = new ExcellentProvider(d.providerId(), logger);
                ep.resolve();
                p = ep;
            } else {
                p = vault;
            }
            currencies.put(d.id(), new Currency(d.id(), d.name(), d.unit(), d.isDefault(), p));
        }
    }

    /** ExcellentEconomy 啟用或停用時重新連接。 */
    public void refreshProviders() {
        for (Currency c : currencies.values()) {
            if (c.provider() instanceof ExcellentProvider ep) {
                ep.resolve();
            }
        }
    }

    public Currency get(String id) {
        return id == null ? null : currencies.get(id);
    }

    public Currency getDefault() {
        for (Currency c : currencies.values()) {
            if (c.isDefault()) {
                return c;
            }
        }
        return currencies.values().iterator().next();
    }

    public Collection<Currency> all() {
        return currencies.values();
    }

    public List<String> ids() {
        return new ArrayList<>(currencies.keySet());
    }

    /** 解析金額輸入；不合法時回傳 null。 */
    public static BigDecimal parseAmount(String input, Currency currency, BigDecimal max) {
        if (input == null) {
            return null;
        }
        String s = input.trim().replace(",", "");
        if (s.isEmpty() || s.length() > 24 || !s.matches("\\d+(\\.\\d+)?")) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(s);
            if (v.signum() <= 0 || v.compareTo(max) > 0 || !currency.validScale(v)) {
                return null;
            }
            return currency.round(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void checkAll() {
        for (Currency c : currencies.values()) {
            if (!c.available()) {
                logger.warning("Currency '" + c.id() + "' is not available right now; items priced in it are paused");
            }
        }
    }
}
