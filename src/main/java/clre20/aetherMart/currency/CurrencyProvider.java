package clre20.aetherMart.currency;

import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 貨幣來源。Player 版本只能在主執行緒、玩家在線時呼叫，結果是同步的；
 * UUID 版本可用於離線玩家，回傳的 future 可能在其他執行緒完成，呼叫端要自己切回主執行緒。
 */
public interface CurrencyProvider {

    boolean available();

    /** 小數位數（0 = 整數）。 */
    int decimals();

    BigDecimal balance(Player player);

    /** 先檢查餘額再扣款；餘額不足回傳 false。 */
    boolean withdraw(Player player, BigDecimal amount);

    boolean deposit(Player player, BigDecimal amount);

    CompletableFuture<BigDecimal> balance(UUID player);

    CompletableFuture<Boolean> withdraw(UUID player, BigDecimal amount);

    CompletableFuture<Boolean> deposit(UUID player, BigDecimal amount);

    /** 從離線玩家扣款，餘額不夠就全部扣光；回傳實際扣到的金額。 */
    CompletableFuture<BigDecimal> withdrawUpTo(UUID player, BigDecimal amount);
}
