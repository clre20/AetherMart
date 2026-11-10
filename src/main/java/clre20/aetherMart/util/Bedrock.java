package clre20.aetherMart.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 判斷是不是基岩版玩家（Floodgate 或 Geyser，以反射呼叫，沒裝時一律當作 Java 版）。
 * 結果依 UUID 快取，玩家離線時清除。
 */
public final class Bedrock {

    private static final Map<UUID, Boolean> CACHE = new ConcurrentHashMap<>();
    private static volatile Object floodgate;
    private static volatile Method floodgateCheck;
    private static volatile Object geyser;
    private static volatile Method geyserCheck;
    private static volatile boolean resolved;

    private Bedrock() {
    }

    public static boolean is(Player p) {
        return CACHE.computeIfAbsent(p.getUniqueId(), Bedrock::lookup);
    }

    public static void forget(UUID id) {
        CACHE.remove(id);
    }

    /** 重載時重新尋找 Floodgate／Geyser（例如之後才裝上）。 */
    public static void reset() {
        CACHE.clear();
        resolved = false;
        floodgate = null;
        geyser = null;
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (Bukkit.getPluginManager().getPlugin("floodgate") != null) {
            try {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgate = api.getMethod("getInstance").invoke(null);
                floodgateCheck = api.getMethod("isFloodgatePlayer", UUID.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                floodgate = null;
            }
        }
        if (Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null) {
            try {
                Class<?> api = Class.forName("org.geysermc.geyser.api.GeyserApi");
                geyser = api.getMethod("api").invoke(null);
                geyserCheck = api.getMethod("isBedrockPlayer", UUID.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                geyser = null;
            }
        }
    }

    private static boolean lookup(UUID id) {
        resolve();
        try {
            if (floodgate != null && (boolean) floodgateCheck.invoke(floodgate, id)) {
                return true;
            }
            if (geyser != null && (boolean) geyserCheck.invoke(geyser, id)) {
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 查不到就當作 Java 版
        }
        return false;
    }
}
