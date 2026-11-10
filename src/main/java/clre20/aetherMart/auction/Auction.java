package clre20.aetherMart.auction;

import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 一筆進行中的拍賣（整包物品）。只在主執行緒修改。 */
public final class Auction {

    public enum Mode { FIXED, BID }

    /** ACTIVE 競價期；KNOCKING 落槌期；SETTLING 結算中。 */
    public enum Phase { ACTIVE, KNOCKING, SETTLING }

    public record Bid(UUID bidder, String name, BigDecimal amount, long time) {
    }

    public final long id;
    public final UUID seller;
    public final String sellerName;
    public final Mode mode;
    public final String currency;
    /** 一口價的售價，或競標的底價。 */
    public final BigDecimal price;
    public final List<ItemStack> items;
    public final long created;
    public final long endsAt;
    public final String listReceipt;
    public Phase phase = Phase.ACTIVE;
    public int knock;
    public long nextKnockAt;
    public final List<Bid> bids = new ArrayList<>();
    /** 搜尋用文字與件數（建立時算好）。 */
    public String searchText = "";
    public int totalItems;

    public Auction(long id, UUID seller, String sellerName, Mode mode, String currency, BigDecimal price,
                   List<ItemStack> items, long created, long endsAt, String listReceipt) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.mode = mode;
        this.currency = currency;
        this.price = price;
        this.items = items;
        this.created = created;
        this.endsAt = endsAt;
        this.listReceipt = listReceipt;
        int n = 0;
        for (ItemStack i : items) {
            n += i.getAmount();
        }
        this.totalItems = n;
    }

    public Bid highest() {
        return bids.isEmpty() ? null : bids.get(bids.size() - 1);
    }

    /** 目前顯示的價格：一口價為售價，競標為最高出價（沒人出價則為底價）。 */
    public BigDecimal currentPrice() {
        Bid h = highest();
        return h == null ? price : h.amount();
    }

    /** 下一個要處理的時間點。 */
    public long nextEventAt() {
        return phase == Phase.KNOCKING ? nextKnockAt : endsAt;
    }

    /** 每位出價者只留最高一次，依金額由高到低（得標順序）。 */
    public List<Bid> candidates() {
        List<Bid> out = new ArrayList<>();
        for (int i = bids.size() - 1; i >= 0; i--) {
            Bid b = bids.get(i);
            boolean seen = false;
            for (Bid o : out) {
                if (o.bidder().equals(b.bidder())) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                out.add(b);
            }
        }
        return out;
    }

    public boolean hasBidFrom(UUID id) {
        for (Bid b : bids) {
            if (b.bidder().equals(id)) {
                return true;
            }
        }
        return false;
    }
}
