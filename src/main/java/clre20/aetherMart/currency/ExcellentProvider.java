package clre20.aetherMart.currency;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * ExcellentEconomy 的貨幣（以反射呼叫它的 ExcellentEconomyAPI 服務，沒裝時不會載入任何它的類別）。
 * ExcellentEconomy 扣款時不檢查餘額，所以一律先查餘額再扣。
 */
public final class ExcellentProvider implements CurrencyProvider {

    private static final String API = "su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI";

    private final String currencyId;
    private final Logger logger;
    private Object api;
    private Class<?> apiClass;
    private Method getCurrency, canOperate, balP, wdP, depP, balA, wdA, depA;
    private boolean warned;

    public ExcellentProvider(String currencyId, Logger logger) {
        this.currencyId = currencyId;
        this.logger = logger;
    }

    /** 重新尋找 API（ExcellentEconomy 啟用／停用、reload 時呼叫）。 */
    public synchronized void resolve() {
        api = null;
        Plugin p = Bukkit.getPluginManager().getPlugin("ExcellentEconomy");
        if (p == null || !p.isEnabled()) {
            return;
        }
        try {
            Class<?> cls = null;
            for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (c.getName().equals(API)) {
                    cls = c;
                    break;
                }
            }
            if (cls == null) {
                return;
            }
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(cls);
            if (rsp == null) {
                return;
            }
            apiClass = cls;
            getCurrency = cls.getMethod("getCurrency", String.class);
            canOperate = cls.getMethod("canPerformOperations");
            balP = cls.getMethod("getBalance", Player.class, String.class);
            wdP = cls.getMethod("withdraw", Player.class, String.class, double.class);
            depP = cls.getMethod("deposit", Player.class, String.class, double.class);
            balA = cls.getMethod("getBalanceAsync", UUID.class, String.class);
            wdA = cls.getMethod("withdrawAsync", UUID.class, String.class, double.class);
            depA = cls.getMethod("depositAsync", UUID.class, String.class, double.class);
            api = rsp.getProvider();
            warned = false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            api = null;
            if (!warned) {
                warned = true;
                logger.warning("ExcellentEconomy API not compatible: " + e);
            }
        }
    }

    private synchronized Object currency() {
        if (api == null) {
            return null;
        }
        try {
            return getCurrency.invoke(api, currencyId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean available() {
        Object c = currency();
        if (c == null) {
            return false;
        }
        try {
            return (boolean) canOperate.invoke(api);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public int decimals() {
        Object c = currency();
        if (c == null) {
            return 0;
        }
        try {
            Method m = c.getClass().getMethod("isDecimal");
            return (boolean) m.invoke(c) ? 2 : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 2;
        }
    }

    @Override
    public BigDecimal balance(Player player) {
        try {
            return BigDecimal.valueOf((double) balP.invoke(api, player, currencyId));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    @Override
    public boolean withdraw(Player player, BigDecimal amount) {
        if (!available()) {
            return false;
        }
        if (balance(player).compareTo(amount) < 0) {
            return false;
        }
        try {
            return (boolean) wdP.invoke(api, player, currencyId, amount.doubleValue());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean deposit(Player player, BigDecimal amount) {
        if (!available()) {
            return false;
        }
        try {
            return (boolean) depP.invoke(api, player, currencyId, amount.doubleValue());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public CompletableFuture<BigDecimal> balance(UUID player) {
        if (!available()) {
            return CompletableFuture.completedFuture(BigDecimal.ZERO);
        }
        try {
            CompletableFuture<Double> f = (CompletableFuture<Double>) balA.invoke(api, player, currencyId);
            return f.thenApply(d -> d == null ? BigDecimal.ZERO : BigDecimal.valueOf(d));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return CompletableFuture.failedFuture(new ProviderException("ExcellentEconomy getBalance failed: " + e, e));
        }
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Boolean> op(Method m, UUID player, BigDecimal amount) {
        try {
            CompletableFuture<Object> f = (CompletableFuture<Object>) m.invoke(api, player, currencyId, amount.doubleValue());
            return f.thenApply(r -> r != null && "SUCCESS".equals(r.toString()));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return CompletableFuture.failedFuture(new ProviderException("ExcellentEconomy operation failed: " + e, e));
        }
    }

    @Override
    public CompletableFuture<Boolean> withdraw(UUID player, BigDecimal amount) {
        if (!available()) {
            return CompletableFuture.completedFuture(false);
        }
        return balance(player).thenCompose(bal -> bal.compareTo(amount) < 0
                ? CompletableFuture.completedFuture(false) : op(wdA, player, amount));
    }

    @Override
    public CompletableFuture<Boolean> deposit(UUID player, BigDecimal amount) {
        if (!available()) {
            return CompletableFuture.completedFuture(false);
        }
        return op(depA, player, amount);
    }

    @Override
    public CompletableFuture<BigDecimal> withdrawUpTo(UUID player, BigDecimal amount) {
        if (!available()) {
            return CompletableFuture.completedFuture(BigDecimal.ZERO);
        }
        return balance(player).thenCompose(bal -> {
            BigDecimal take = bal.min(amount);
            if (take.signum() <= 0) {
                return CompletableFuture.completedFuture(BigDecimal.ZERO);
            }
            return op(wdA, player, take).thenApply(ok -> ok ? take : BigDecimal.ZERO);
        });
    }

    Class<?> apiClass() {
        return apiClass;
    }
}
