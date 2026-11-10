package clre20.aetherMart.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** {變數} 對照表的簡便建構。 */
public final class Vars {

    private Vars() {
    }

    public static Map<String, String> of(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]));
        }
        return m;
    }

    public static Map<String, String> with(Map<String, String> base, Object... kv) {
        Map<String, String> m = new LinkedHashMap<>(base);
        m.putAll(of(kv));
        return m;
    }
}
