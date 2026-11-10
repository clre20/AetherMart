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

/** 預設幣：Vault。所有呼叫都在主執行緒進行（UUID 版本也是同步完成）。 */
public final class VaultProvider implements CurrencyProvider {

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

    private static OfflinePlayer offline(UUID id) {
        return Bukkit.getOfflinePlayer(id);
    }

    @Override
    public BigDecimal balance(Player player) {
        Economy e = economy();
        return e == null ? BigDecimal.ZERO : BigDecimal.valueOf(e.getBalance(player));
    }

    @Override
    public boolean withdraw(Player player, BigDecimal amount) {
        return withdrawSync(player, amount);
    }

    @Override
    public boolean deposit(Player player, BigDecimal amount) {
        return depositSync(player, amount);
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
        return CompletableFuture.completedFuture(e == null ? BigDecimal.ZERO : BigDecimal.valueOf(e.getBalance(offline(player))));
    }

    @Override
    public CompletableFuture<Boolean> withdraw(UUID player, BigDecimal amount) {
        return CompletableFuture.completedFuture(withdrawSync(offline(player), amount));
    }

    @Override
    public CompletableFuture<Boolean> deposit(UUID player, BigDecimal amount) {
        return CompletableFuture.completedFuture(depositSync(offline(player), amount));
    }

    @Override
    public CompletableFuture<BigDecimal> withdrawUpTo(UUID player, BigDecimal amount) {
        Economy e = economy();
        if (e == null) {
            return CompletableFuture.completedFuture(BigDecimal.ZERO);
        }
        OfflinePlayer p = offline(player);
        double bal = Math.max(0, e.getBalance(p));
        double take = Math.min(bal, amount.doubleValue());
        if (take <= 0) {
            return CompletableFuture.completedFuture(BigDecimal.ZERO);
        }
        EconomyResponse r = e.withdrawPlayer(p, take);
        return CompletableFuture.completedFuture(r != null && r.transactionSuccess() ? BigDecimal.valueOf(take) : BigDecimal.ZERO);
    }
}
