package clre20.aetherMart.lang;

import clre20.aetherMart.util.Texts;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * 物品名稱：有自訂名稱就用自訂名稱；否則用 config 語言的原版翻譯。
 * 英文從伺服器 jar 讀；繁中／簡中第一次需要時在背景向 Mojang 官方資源伺服器下載並快取。
 */
public final class ItemNames {

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String RESOURCES = "https://resources.download.minecraft.net/";
    private static final long MAX_JSON = 32L * 1024 * 1024;

    private final JavaPlugin plugin;
    private volatile Map<String, String> table = Map.of();
    private volatile Map<String, String> english = Map.of();

    public ItemNames(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 在背景執行緒載入（必要時下載）翻譯表。 */
    public void load(String language, ExecutorService worker) {
        worker.execute(() -> {
            english = readEnglish();
            if (language.equals("en_us")) {
                table = english;
                return;
            }
            String version = Bukkit.getMinecraftVersion();
            Path dir = new File(plugin.getDataFolder(), "cache/lang/" + safe(version)).toPath();
            Path file = dir.resolve(language + ".json");
            try {
                if (!Files.isRegularFile(file)) {
                    download(version, language, file);
                    cleanupOldVersions(dir);
                }
                table = parse(Files.newInputStream(file));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Cannot load " + language + " item names, using English: " + e.getMessage());
                table = english;
            }
        });
    }

    private Map<String, String> readEnglish() {
        String[] paths = {"assets/minecraft/lang/en_us.json", "/assets/minecraft/lang/en_us.json"};
        ClassLoader[] loaders = {Bukkit.getServer().getClass().getClassLoader(), Bukkit.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader()};
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            for (String p : paths) {
                try (InputStream in = cl.getResourceAsStream(p.startsWith("/") ? p.substring(1) : p)) {
                    if (in != null) {
                        return parse(in);
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return Map.of();
    }

    private static Map<String, String> parse(InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            Map<String, String> m = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                String k = e.getKey();
                if (k.startsWith("item.") || k.startsWith("block.") || k.startsWith("enchantment.")) {
                    m.put(k, e.getValue().getAsString());
                }
            }
            return Map.copyOf(m);
        }
    }

    private void download(String version, String language, Path target) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JsonObject manifest = json(http, MANIFEST);
        String versionUrl = null;
        for (JsonElement v : manifest.getAsJsonArray("versions")) {
            JsonObject vo = v.getAsJsonObject();
            if (version.equals(vo.get("id").getAsString())) {
                versionUrl = vo.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IOException("version " + version + " not in Mojang manifest");
        }
        JsonObject vjson = json(http, versionUrl);
        String indexUrl = vjson.getAsJsonObject("assetIndex").get("url").getAsString();
        JsonObject index = json(http, indexUrl);
        JsonObject obj = index.getAsJsonObject("objects").getAsJsonObject("minecraft/lang/" + language + ".json");
        if (obj == null) {
            throw new IOException("language " + language + " not in asset index");
        }
        String hash = obj.get("hash").getAsString().toLowerCase(Locale.ROOT);
        if (!hash.matches("[0-9a-f]{40}")) {
            throw new IOException("bad asset hash");
        }
        byte[] data = bytes(http, RESOURCES + hash.substring(0, 2) + "/" + hash);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        if (!actual.equals(hash)) {
            throw new IOException("hash mismatch");
        }
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        plugin.getLogger().info("Downloaded " + language + " item names for " + version);
    }

    private static JsonObject json(HttpClient http, String url) throws Exception {
        return JsonParser.parseString(new String(bytes(http, url), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] bytes(HttpClient http, String url) throws Exception {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (!"https".equals(uri.getScheme()) || !(host.endsWith(".mojang.com") || host.endsWith(".minecraft.net"))) {
            throw new IOException("unexpected host " + host);
        }
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "AetherMart").GET().build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode());
        }
        if (res.body().length > MAX_JSON) {
            throw new IOException("response too large");
        }
        return res.body();
    }

    private void cleanupOldVersions(Path current) {
        Path parent = current.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        try (Stream<Path> s = Files.list(parent)) {
            s.filter(p -> !p.equals(current) && Files.isDirectory(p)).forEach(p -> {
                try (Stream<Path> files = Files.list(p)) {
                    files.forEach(f -> {
                        try {
                            Files.deleteIfExists(f);
                        } catch (IOException ignored) {
                        }
                    });
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String safe(String v) {
        return v.replaceAll("[^0-9A-Za-z._-]", "_");
    }

    // ------------------------------------------------------------------

    public String translate(String key, String fallback) {
        String v = table.get(key);
        if (v == null) {
            v = english.get(key);
        }
        return v == null ? fallback : v;
    }

    public String material(Material m) {
        return translate(m.translationKey(), pretty(m.name()));
    }

    /** 物品顯示名稱：自訂名稱 → 物品名稱元件 → 原版翻譯。 */
    public String name(ItemStack item) {
        if (item == null) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                String s = Texts.plain(meta.displayName());
                if (!s.isBlank()) {
                    return s;
                }
            }
            try {
                if (meta.hasItemName()) {
                    String s = Texts.plain(meta.itemName());
                    if (!s.isBlank()) {
                        return s;
                    }
                }
            } catch (NoSuchMethodError ignored) {
                // 舊版 API 沒有 item_name
            }
        }
        return material(item.getType());
    }

    public String enchantment(Enchantment e) {
        String key = e.translationKey();
        return translate(key, pretty(e.getKey().getKey()));
    }

    /** 附魔與耐久摘要，例如「鋒利 V、耐久 412／432」。 */
    public String remark(ItemStack item, String durabilityLabel) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Map<Enchantment, Integer> ench = new HashMap<>(meta.getEnchants());
        if (meta instanceof EnchantmentStorageMeta esm) {
            ench.putAll(esm.getStoredEnchants());
        }
        ench.entrySet().stream()
                .sorted((a, b) -> a.getKey().getKey().getKey().compareTo(b.getKey().getKey().getKey()))
                .forEach(en -> {
                    if (!sb.isEmpty()) {
                        sb.append("、");
                    }
                    sb.append(enchantment(en.getKey()));
                    if (en.getKey().getMaxLevel() > 1 || en.getValue() > 1) {
                        sb.append(' ').append(roman(en.getValue()));
                    }
                });
        if (meta instanceof Damageable d && item.getType().getMaxDurability() > 0 && d.hasDamage()) {
            int max = item.getType().getMaxDurability();
            if (!sb.isEmpty()) {
                sb.append("、");
            }
            sb.append(durabilityLabel).append(' ').append(max - d.getDamage()).append("／").append(max);
        }
        return sb.toString();
    }

    public static String roman(int n) {
        if (n <= 0 || n > 3999) {
            return String.valueOf(n);
        }
        String[] r = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                sb.append(r[i]);
                n -= v[i];
            }
        }
        return sb.toString();
    }

    static String pretty(String id) {
        String[] parts = id.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    /** 搜尋用：小寫名稱＋物品 ID。 */
    public String searchText(ItemStack item) {
        return (name(item) + " " + item.getType().getKey().getKey() + " " + material(item.getType())).toLowerCase(Locale.ROOT);
    }
}
