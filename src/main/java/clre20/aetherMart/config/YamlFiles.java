package clre20.aetherMart.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.logging.Level;

/**
 * 設定檔自動補齊：以內建預設檔（含註解）為底，填回使用者原本的值，移除不再使用的項目。
 * freeSections 底下的內容由使用者自由增減（例如各幣別的設定），整段保留。
 */
public final class YamlFiles {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private YamlFiles() {
    }

    /**
     * 文字檔（messages.yml、pdf.yml）開頭有 language 標記；config.yml 換了語言時，
     * 舊檔改名成 messages-zh_tw.yml 之類的備份，再用新語言的預設檔重建。
     */
    public static void switchLanguage(JavaPlugin plugin, File file, String language) {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            return;
        }
        String old = y.getString("language");
        if (old == null || old.equalsIgnoreCase(language) || !old.matches("[a-zA-Z_]{2,10}")) {
            return;
        }
        String base = file.getName().replaceFirst("\\.yml$", "");
        File backup = new File(file.getParentFile(), base + "-" + old.toLowerCase() + ".yml");
        if (backup.exists()) {
            backup = new File(file.getParentFile(), base + "-" + old.toLowerCase() + "-" + LocalDateTime.now().format(STAMP) + ".yml");
        }
        try {
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Language changed to " + language + ": " + file.getName() + " was moved to " + backup.getName());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Cannot back up " + file.getName(), e);
        }
    }

    public static YamlConfiguration loadDefaults(JavaPlugin plugin, String resource) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().parseComments(true);
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + resource);
            }
            y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("bad resource " + resource, e);
        }
        return y;
    }

    /**
     * @param file         使用者的檔案
     * @param resource     內建預設檔路徑
     * @param freeSections 自由增減的區段路徑
     */
    public static YamlConfiguration load(JavaPlugin plugin, File file, String resource, Set<String> freeSections) {
        YamlConfiguration defaults = loadDefaults(plugin, resource);
        if (!file.exists()) {
            try (InputStream in = plugin.getResource(resource)) {
                Files.createDirectories(file.getParentFile().toPath());
                Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Cannot create " + file.getName(), e);
            }
            return defaults;
        }

        String original;
        YamlConfiguration user = new YamlConfiguration();
        user.options().parseComments(true);
        try {
            original = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            user.loadFromString(original);
        } catch (IOException | InvalidConfigurationException e) {
            backup(plugin, file, ".broken-" + LocalDateTime.now().format(STAMP));
            plugin.getLogger().warning(file.getName() + " has a YAML error, using defaults for now (backup saved): " + e.getMessage());
            return defaults;
        }

        YamlConfiguration merged = loadDefaults(plugin, resource);
        boolean removed = false;
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path) || isUnderFree(path, freeSections)) {
                continue;
            }
            if (user.contains(path, true) && !user.isConfigurationSection(path)) {
                merged.set(path, user.get(path));
            }
        }
        for (String free : freeSections) {
            if (user.isConfigurationSection(free)) {
                merged.set(free, null);
                copySection(user.getConfigurationSection(free), merged.createSection(free));
            }
        }
        for (String path : user.getKeys(true)) {
            if (user.isConfigurationSection(path) || isUnderFree(path, freeSections)) {
                continue;
            }
            if (!defaults.contains(path, true)) {
                removed = true;
                break;
            }
        }

        String result = merged.saveToString();
        if (!result.equals(original)) {
            if (removed) {
                backup(plugin, file, ".bak");
            }
            write(plugin, file, result);
        }
        return merged;
    }

    private static boolean isUnderFree(String path, Set<String> free) {
        for (String f : free) {
            if (path.equals(f) || path.startsWith(f + ".")) {
                return true;
            }
        }
        return false;
    }

    private static void copySection(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            if (from.isConfigurationSection(key)) {
                copySection(from.getConfigurationSection(key), to.createSection(key));
            } else {
                to.set(key, from.get(key));
            }
        }
    }

    public static void write(JavaPlugin plugin, File file, String content) {
        try {
            Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Cannot write " + file.getName(), ex);
            }
        }
    }

    private static void backup(JavaPlugin plugin, File file, String suffix) {
        try {
            Files.copy(file.toPath(), file.toPath().resolveSibling(file.getName() + suffix), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Cannot back up " + file.getName(), e);
        }
    }
}
