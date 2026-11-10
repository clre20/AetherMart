package clre20.aetherMart.config;

import clre20.aetherMart.util.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** messages.yml：所有遊戲內、介面、Discord 卡片、後台文字。 */
public final class Messages {

    private YamlConfiguration yaml = new YamlConfiguration();
    private YamlConfiguration fallback = new YamlConfiguration();
    private String prefix = "";

    public void load(JavaPlugin plugin, String language, String prefix) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        YamlFiles.switchLanguage(plugin, file, language);
        String res = "defaults/" + language + "/messages.yml";
        this.fallback = YamlFiles.loadDefaults(plugin, res);
        this.yaml = YamlFiles.load(plugin, file, res, Set.of());
        this.prefix = prefix == null ? "" : prefix;
    }

    public String raw(String key) {
        String s = yaml.getString(key);
        if (s == null) {
            s = fallback.getString(key);
        }
        return s == null ? key : s;
    }

    public List<String> list(String key) {
        if (yaml.isList(key)) {
            return yaml.getStringList(key);
        }
        if (fallback.isList(key)) {
            return fallback.getStringList(key);
        }
        String s = yaml.getString(key, fallback.getString(key));
        return s == null ? List.of() : List.of(s);
    }

    public Component get(String key, Map<String, String> vars) {
        return Texts.render(raw(key), vars);
    }

    public String plain(String key, Map<String, String> vars) {
        return Texts.plain(raw(key), vars);
    }

    public void send(CommandSender to, String key, Map<String, String> vars) {
        String t = raw(key);
        if (t.isEmpty()) {
            return;
        }
        to.sendMessage(Texts.render(prefix + t, vars));
    }

    public void send(CommandSender to, String key) {
        send(to, key, Map.of());
    }

    public void sendRaw(CommandSender to, String key, Map<String, String> vars) {
        to.sendMessage(Texts.render(raw(key), vars));
    }

    public String prefix() {
        return prefix;
    }
}
