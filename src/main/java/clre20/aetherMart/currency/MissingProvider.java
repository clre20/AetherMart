package clre20.aetherMart.currency;

import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 需要的經濟插件沒有安裝時使用：一律回報無法使用，所有交易都會被擋下。 */
final class MissingProvider implements CurrencyProvider {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public int decimals() {
        return 2;
    }

    @Override
    public BigDecimal balance(Player player) {
        return BigDecimal.ZERO;
    }

    @Override
    public boolean withdraw(Player player, BigDecimal amount) {
        return false;
    }

    @Override
    public boolean deposit(Player player, BigDecimal amount) {
        return false;
    }

    @Override
    public CompletableFuture<BigDecimal> balance(UUID player) {
        return CompletableFuture.completedFuture(BigDecimal.ZERO);
    }

    @Override
    public CompletableFuture<Boolean> withdraw(UUID player, BigDecimal amount) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletableFuture<Boolean> deposit(UUID player, BigDecimal amount) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletableFuture<BigDecimal> withdrawUpTo(UUID player, BigDecimal amount) {
        return CompletableFuture.completedFuture(BigDecimal.ZERO);
    }
}
