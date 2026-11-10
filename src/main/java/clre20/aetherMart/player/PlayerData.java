package clre20.aetherMart.player;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** 線上玩家的快取資料：通知設定、受罰狀態、欠款、購買次數。只在主執行緒存取。 */
public final class PlayerData {

    public static final int N_PURCHASE = 1;
    public static final int N_WON = 1 << 1;
    public static final int N_SOLD = 1 << 2;
    public static final int N_LISTED = 1 << 3;
    public static final int N_REMOVED = 1 << 4;
    public static final int N_EXPIRED = 1 << 5;
    public static final int N_OUTBID = 1 << 6;
    public static final int[] ALL = {N_PURCHASE, N_WON, N_SOLD, N_LISTED, N_REMOVED, N_EXPIRED, N_OUTBID};

    /** 被關掉的通知（位元）；0 = 全開。 */
    public int disabledNotify;
    public long bannedUntil;
    public String banReason;
    public final Map<String, BigDecimal> debts = new HashMap<>();
    /** 「商品ID|期間」→ 已購買數量。 */
    public final Map<String, Integer> purchases = new HashMap<>();

    public boolean notifyEnabled(int flag) {
        return (disabledNotify & flag) == 0;
    }

    public boolean banned(long now) {
        return bannedUntil > now;
    }

    public boolean hasDebt() {
        for (BigDecimal d : debts.values()) {
            if (d.signum() > 0) {
                return true;
            }
        }
        return false;
    }
}
