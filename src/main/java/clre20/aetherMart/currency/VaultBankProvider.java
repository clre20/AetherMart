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
 * VaultBank 的幣種（例如企鵝幣），以反射呼叫 VaultBank 的 VaultBankAPI 服務，沒裝 VaultBank 時不會載入任何它的類別。
 * VaultBank 的存提是同步且執行緒安全的（每個帳戶各自上鎖、餘額不足時不扣款），所以 UUID 版本直接回傳完成的結果。
 */
public final class VaultBankProvider implements CurrencyProvider {

    private static final String API = "clre20.vaultBank.api.VaultBankAPI";
    private static final String SOURCE = "AetherMart";

    private final String currencyId;
    private final Logger logger;
    private volatile Object api;
    private Method isEnabled;
    private Method decimalPlaces;
    private Method balance;
    private Method withdraw;
    private Method deposit;
    private boolean warned;

    public VaultBankProvider(String currencyId, Logger logger) {
        this.currencyId = currencyId;
        this.logger = logger;
    }

    /** 重新尋找 API（VaultBank 啟用／停用、reload 時呼叫）。 */
    public synchronized void resolve() {
        api = null;
        Plugin p = Bukkit.getPluginManager().getPlugin("VaultBank");
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
            isEnabled = cls.getMethod("isEnabled", String.class);
            decimalPlaces = cls.getMethod("decimalPlaces", String.class);
            balance = cls.getMethod("balance", UUID.class, String.class);
            withdraw = cls.getMethod("withdraw", UUID.class, String.class, BigDecimal.class, String.class);
            deposit = cls.getMethod("deposit", UUID.class, String.class, BigDecimal.class, String.class);
            api = rsp.getProvider();
            warned = false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            api = null;
            if (!warned) {
                warned = true;
                logger.warning("VaultBank API not compatible: " + e);
            }
        }
    }

    @Override
    public boolean available() {
        Object a = api;
        if (a == null) {
            return false;
        }
        try {
            return (boolean) isEnabled.invoke(a, currencyId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public int decimals() {
        Object a = api;
        if (a == null) {
            return 0;
        }
        try {
            return (int) decimalPlaces.invoke(a, currencyId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private BigDecimal bal(UUID id) {
        Object a = api;
        if (a == null) {
            return BigDecimal.ZERO;
        }
        try {
            return (BigDecimal) balance.invoke(a, id, currencyId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    private boolean call(Method m, UUID id, BigDecimal amount) {
        Object a = api;
        if (a == null || amount == null || amount.signum() <= 0) {
            return false;
        }
        try {
            return (boolean) m.invoke(a, id, currencyId, amount, SOURCE);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public BigDecimal balance(Player player) {
        return bal(player.getUniqueId());
    }

    /** VaultBank 餘額不足時本身就不會扣款。 */
    @Override
    public boolean withdraw(Player player, BigDecimal amount) {
        return call(withdraw, player.getUniqueId(), amount);
    }

    @Override
    public boolean deposit(Player player, BigDecimal amount) {
        return call(deposit, player.getUniqueId(), amount);
    }

    @Override
    public CompletableFuture<BigDecimal> balance(UUID player) {
        if (api == null) {
            return CompletableFuture.failedFuture(new ProviderException("VaultBank unavailable", null));
        }
        return CompletableFuture.completedFuture(bal(player));
    }

    @Override
    public CompletableFuture<Boolean> withdraw(UUID player, BigDecimal amount) {
        return CompletableFuture.completedFuture(call(withdraw, player, amount));
    }

    @Override
    public CompletableFuture<Boolean> deposit(UUID player, BigDecimal amount) {
        return CompletableFuture.completedFuture(call(deposit, player, amount));
    }

    @Override
    public CompletableFuture<BigDecimal> withdrawUpTo(UUID player, BigDecimal amount) {
        if (api == null) {
            return CompletableFuture.failedFuture(new ProviderException("VaultBank unavailable", null));
        }
        // 餘額可能同時被別的地方改動：失敗就用最新餘額再試幾次
        for (int i = 0; i < 3; i++) {
            BigDecimal take = bal(player).min(amount);
            if (take.signum() <= 0) {
                return CompletableFuture.completedFuture(BigDecimal.ZERO);
            }
            if (call(withdraw, player, take)) {
                return CompletableFuture.completedFuture(take);
            }
        }
        return CompletableFuture.completedFuture(BigDecimal.ZERO);
    }
}
