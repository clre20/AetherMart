package clre20.aetherMart.currency;

/**
 * 經濟插件本身出錯（丟例外、玩家資料不存在等），不是「餘額不足」。
 * 拍賣結算遇到這種錯誤時延後重試，不會當成得標者付不出錢。
 */
public final class ProviderException extends RuntimeException {

    public ProviderException(String message, Throwable cause) {
        super(message, cause, false, false);
    }
}
