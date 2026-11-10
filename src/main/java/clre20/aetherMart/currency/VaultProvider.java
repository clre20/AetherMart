package clre20.aetherMart.currency;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * 預設幣：Vault。所有呼叫都在主執行緒進行（UUID 版本也是同步完成）。
 * 經濟插件丟出的例外一律在這裡接住：玩家版本回傳 false／0；UUID 版本以 {@link ProviderException} 結束，
 * 讓拍賣結算知道是「經濟插件出錯」而不是「餘額不足」。
 */
public final class VaultProvider implements CurrencyProvider {

    private final Logger logger;
    private long lastWarn;

    public VaultProvider(Logger logger) {
        this.logger = logger;
    }

    private void warn(String op, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastWarn > 60_000L) {
            lastWarn = now;
            logger.warning("Vault economy error during " + op + ": " + t);
        }
    }

    private static <T> CompletableFuture<T> failed(String op, Throwable t) {
        return CompletableFuture.failedFuture(new ProviderException("Vault " + op + " failed: " + t.getMessage(), t));
    }

    /** 沒進過這台伺服器的玩家：有些經濟插件（例如 TheosisEconomy）會因為名稱是 null 而出錯，先擋下來。 */
    private static OfflinePlayer known(UUID id) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(id);
        if (p.isOnline() || p.getName() != null) {
            return p;
        }
        throw new IllegalStateException("player " + id + " has no known name on this server");
    }

    private Economy economy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    @Override
    public boolean available() {
        Economy e = economy();
        return e != null && e.isEnabled();
    }

    @Override
    public int decimals() {
        Economy e = economy();
        if (e == null) {
            return 2;
        }
        int d = e.fractionalDigits();
        return d < 0 ? 2 : Math.min(d, 4);
    }

    @Override
    public BigDecimal balance(Player player) {
        Economy e = economy();
        try {
            return e == null ? BigDecimal.ZERO : BigDecimal.valueOf(e.getBalance(player));
        } catch (RuntimeException ex) {
            warn("getBalance", ex);
            return BigDecimal.ZERO;
        }
    }

    @Override
    public boolean withdraw(Player player, BigDecimal amount) {
        try {
            return withdrawSync(player, amount);
        } catch (RuntimeException ex) {
            warn("withdraw", ex);
            return false;
        }
    }

    @Override
    public boolean deposit(Player player, BigDecimal amount) {
        try {
            return depositSync(player, amount);
        } catch (RuntimeException ex) {
            warn("deposit", ex);
            return false;
        }
    }

    private boolean withdrawSync(OfflinePlayer p, BigDecimal amount) {
        Economy e = economy();
        if (e == null) {
            return false;
        }
        double v = amount.doubleValue();
        if (!e.has(p, v)) {
            return false;
        }
        EconomyResponse r = e.withdrawPlayer(p, v);
        return r != null && r.transactionSuccess();
    }

    private boolean depositSync(OfflinePlayer p, BigDecimal amount) {
        Economy e = economy();
        if (e == null) {
            return false;
        }
        if (!e.hasAccount(p)) {
            e.createPlayerAccount(p);
        }
        EconomyResponse r = e.depositPlayer(p, amount.doubleValue());
        return r != null && r.transactionSuccess();
    }

    @Override
    public CompletableFuture<BigDecimal> balance(UUID player) {
        Economy e = economy();
        try {
            return CompletableFuture.completedFuture(e == null ? BigDecimal.ZERO : BigDecimal.valueOf(e.getBalance(known(player))));
        } catch (RuntimeException ex) {
            warn("getBalance", ex);
            return failed("getBalance", ex);
        }
    }

    @Override
    public CompletableFuture<Boolean> withdraw(UUID player, BigDecimal amount) {
        try {
            return CompletableFuture.completedFuture(withdrawSync(known(player), amount));
        } catch (RuntimeException ex) {
            warn("withdraw", ex);
            return failed("withdraw", ex);
        }
    }

    @Override
    public CompletableFuture<Boolean> deposit(UUID player, BigDecimal amount) {
        try {
            return CompletableFuture.completedFuture(depositSync(known(player), amount));
        } catch (RuntimeException ex) {
            warn("deposit", ex);
            return failed("deposit", ex);
        }
    }

    @Override
    public CompletableFuture<BigDecimal> withdrawUpTo(UUID player, BigDecimal amount) {
        Economy e = economy();
        if (e == null) {
            return CompletableFuture.completedFuture(BigDecimal.ZERO);
        }
        try {
            OfflinePlayer p = known(player);
            double bal = Math.max(0, e.getBalance(p));
            double take = Math.min(bal, amount.doubleValue());
            if (take <= 0) {
                return CompletableFuture.completedFuture(BigDecimal.ZERO);
            }
            EconomyResponse r = e.withdrawPlayer(p, take);
            return CompletableFuture.completedFuture(r != null && r.transactionSuccess() ? BigDecimal.valueOf(take) : BigDecimal.ZERO);
        } catch (RuntimeException ex) {
            warn("withdraw", ex);
            return failed("withdraw", ex);
        }
    }
}
