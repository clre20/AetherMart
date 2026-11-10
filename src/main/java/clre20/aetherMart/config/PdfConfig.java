package clre20.aetherMart.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.io.File;
import java.util.Set;

/** pdf.yml：PDF 的名稱、顏色、字型與所有文字。 */
public final class PdfConfig {

    public record TypeStyle(String title, String subtitle, String stamp, Color color) {
    }

    private volatile YamlConfiguration yaml = new YamlConfiguration();
    private volatile YamlConfiguration fallback = new YamlConfiguration();

    public void load(JavaPlugin plugin, String language) {
        File file = new File(plugin.getDataFolder(), "pdf.yml");
        YamlFiles.switchLanguage(plugin, file, language);
        String res = "defaults/" + language + "/pdf.yml";
        fallback = YamlFiles.loadDefaults(plugin, res);
        yaml = YamlFiles.load(plugin, file, res, Set.of());
    }

    public String text(String path) {
        String s = yaml.getString(path);
        if (s == null) {
            s = fallback.getString(path);
        }
        return s == null ? path : s;
    }

    public String serverName() {
        return text("server-name");
    }

    public String subtitle() {
        return text("subtitle");
    }

    public String fontFile(boolean bold) {
        return yaml.getString(bold ? "font.bold" : "font.regular", "").trim();
    }

    public Color theme() {
        return color(text("colors.theme"), new Color(0x1F5F6B));
    }

    public TypeStyle type(String kind) {
        String base = "text.types." + kind + ".";
        String colorKey = text(base + "color");
        Color c = color(text("colors.stamp." + colorKey), theme());
        return new TypeStyle(text(base + "title"), text(base + "subtitle"), text(base + "stamp"), c);
    }

    static Color color(String hex, Color fallback) {
        if (hex == null) {
            return fallback;
        }
        String h = hex.trim();
        if (h.startsWith("#")) {
            h = h.substring(1);
        }
        if (!h.matches("[0-9a-fA-F]{6}")) {
            return fallback;
        }
        return new Color(Integer.parseInt(h, 16));
    }
}
