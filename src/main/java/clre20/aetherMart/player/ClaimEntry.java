package clre20.aetherMart.player;

import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 領取箱的一筆：一整包物品或一筆錢。 */
public final class ClaimEntry {

    public enum Kind { ITEMS, MONEY }

    /** 來源，對應 messages.yml 的 claim.source.*。 */
    public enum Source { BOUGHT, WON, EXPIRED, CANCELLED, REMOVED, RETURNED, INCOME, REFUND }

    public volatile long id;
    public final UUID owner;
    public final Kind kind;
    public List<ItemStack> items;
    public final String currency;
    public final BigDecimal amount;
    public final Source source;
    public final long created;
    /** 正在處理中（避免重複領取）。 */
    public boolean busy;

    public ClaimEntry(long id, UUID owner, Kind kind, List<ItemStack> items, String currency, BigDecimal amount, Source source, long created) {
        this.id = id;
        this.owner = owner;
        this.kind = kind;
        this.items = items;
        this.currency = currency;
        this.amount = amount;
        this.source = source;
        this.created = created;
    }

    public static ClaimEntry items(UUID owner, List<ItemStack> items, Source source) {
        return new ClaimEntry(0, owner, Kind.ITEMS, items, null, null, source, System.currentTimeMillis());
    }

    public static ClaimEntry money(UUID owner, String currency, BigDecimal amount, Source source) {
        return new ClaimEntry(0, owner, Kind.MONEY, List.of(), currency, amount, source, System.currentTimeMillis());
    }
}
