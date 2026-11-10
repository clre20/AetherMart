package clre20.aetherMart.data;

import java.util.UUID;

/**
 * 一筆交易紀錄（資料庫，給介面查詢與每日彙整）。
 * SALE：actor = 買家（得標者），other = 賣家；SHOP_BUY：actor = 買家。
 */
public record TradeRecord(long id, long time, Type type, UUID actor, String actorName, UUID other, String otherName,
                          String currency, String amount, String tax, String summary, long auctionId, String receipt) {

    public enum Type {
        SHOP_BUY, LIST, BID, SALE, SALE_BID, CANCEL, FORCE_REMOVE, EXPIRE, PENALTY;

        public boolean isSale() {
            return this == SHOP_BUY || this == SALE || this == SALE_BID;
        }

        public static Type parse(String s) {
            try {
                return valueOf(s);
            } catch (IllegalArgumentException | NullPointerException e) {
                return null;
            }
        }
    }
}
