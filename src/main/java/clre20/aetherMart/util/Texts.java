package clre20.aetherMart.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 文字工具：& 色碼（含 &#RRGGBB）、{變數} 取代、純文字。 */
public final class Texts {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u200B-\\u200F\\u2028-\\u202E\\u2060-\\u206F\\uFEFF]");
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[&§](#[0-9a-f]{6}|[0-9a-fk-orx])");

    private Texts() {
    }

    /** 把含 & 色碼的範本轉成元件，{key} 以純文字插入（玩家輸入的內容不會被當成色碼）。 */
    public static Component render(String template, Map<String, String> vars) {
        Component c = AMP.deserialize(template == null ? "" : template);
        if (vars != null && !vars.isEmpty()) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                String token = "{" + e.getKey() + "}";
                if (template != null && template.contains(token)) {
                    String value = e.getValue() == null ? "" : e.getValue();
                    c = c.replaceText(TextReplacementConfig.builder().matchLiteral(token)
                            .replacement(Component.text(value)).build());
                }
            }
        }
        return c;
    }

    /** 介面物品用：關掉斜體。 */
    public static Component item(String template, Map<String, String> vars) {
        return render(template, vars).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> itemLines(List<String> templates, Map<String, String> vars) {
        List<Component> out = new ArrayList<>(templates.size());
        for (String t : templates) {
            out.add(item(t, vars));
        }
        return out;
    }

    /** 純文字（去掉色碼）。 */
    public static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** 範本套用變數後的純文字（給 PDF、Discord、.log）。 */
    public static String plain(String template, Map<String, String> vars) {
        String s = template == null ? "" : template;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                s = s.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return stripCodes(s);
    }

    public static String stripCodes(String s) {
        return s == null ? "" : LEGACY_CODE.matcher(s).replaceAll("");
    }

    /** 玩家輸入清理：去掉控制字元與色碼、去頭尾空白、限制長度。 */
    public static String sanitizeInput(String s, int maxLength) {
        if (s == null) {
            return "";
        }
        String t = CONTROL.matcher(s).replaceAll("");
        t = stripCodes(t).strip();
        if (t.length() > maxLength) {
            t = t.substring(0, maxLength);
        }
        return t;
    }

    /** 管理員輸入的名稱（允許 & 色碼），去掉控制字元並限制長度。 */
    public static String sanitizeColored(String s, int maxLength) {
        if (s == null) {
            return "";
        }
        String t = CONTROL.matcher(s).replaceAll("").strip();
        if (t.length() > maxLength) {
            t = t.substring(0, maxLength);
        }
        return t;
    }

    public static String legacySection(Component c) {
        return SECTION.serialize(c);
    }

    public static Component fromSection(String s) {
        return SECTION.deserialize(s == null ? "" : s);
    }
}
