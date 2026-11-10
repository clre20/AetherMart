package clre20.aetherMart.pdf;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * PDF 字型：內建 Noto Sans TC／SC（Regular、Bold），或 pdf.yml 指定的 fonts/ 底下 TTF。
 * 第一次產生 PDF 時才載入，閒置 5 分鐘後釋放記憶體。只在持有 PdfRenderer 鎖時使用。
 */
final class PdfFonts {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._ -]{1,80}\\.ttf");
    private static final long MAX_SIZE = 64L * 1024 * 1024;
    private static final long IDLE_MS = 5 * 60_000L;

    /** 一組字型：先用 primary，缺字用 secondary。 */
    static final class Face {
        final TrueTypeFont primary;
        final TrueTypeFont secondary;
        final CmapLookup primaryMap;
        final CmapLookup secondaryMap;

        Face(TrueTypeFont primary, TrueTypeFont secondary) throws IOException {
            this.primary = primary;
            this.secondary = secondary;
            this.primaryMap = primary.getUnicodeCmapLookup(false);
            this.secondaryMap = secondary == null ? null : secondary.getUnicodeCmapLookup(false);
        }

        /** 0 = primary 有、1 = secondary 有、-1 = 都沒有。 */
        int which(int codePoint) {
            if (primaryMap != null && primaryMap.getGlyphId(codePoint) > 0) {
                return 0;
            }
            if (secondaryMap != null && secondaryMap.getGlyphId(codePoint) > 0) {
                return 1;
            }
            return -1;
        }
    }

    private final JavaPlugin plugin;
    private Face regular;
    private Face bold;
    private String loadedKey = "";
    private long lastUse;

    PdfFonts(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    Face regular() {
        return regular;
    }

    Face bold() {
        return bold;
    }

    /** 依語言與自訂字型載入（已載入且設定沒變就直接用）。 */
    void ensure(String language, String customRegular, String customBold) throws IOException {
        lastUse = System.currentTimeMillis();
        String key = language + "|" + customRegular + "|" + customBold;
        if (regular != null && key.equals(loadedKey)) {
            return;
        }
        release();
        boolean sc = language.equals("zh_cn");
        String first = sc ? "SC" : "TC";
        String second = sc ? "TC" : "SC";
        TrueTypeFont r1 = custom(customRegular);
        TrueTypeFont b1 = custom(customBold);
        TrueTypeFont rp = r1 != null ? r1 : builtIn("NotoSans" + first + "-Regular.ttf");
        TrueTypeFont rs = builtIn("NotoSans" + second + "-Regular.ttf");
        TrueTypeFont bp = b1 != null ? b1 : builtIn("NotoSans" + first + "-Bold.ttf");
        TrueTypeFont bs = builtIn("NotoSans" + second + "-Bold.ttf");
        regular = new Face(rp, rs);
        bold = new Face(bp, bs);
        loadedKey = key;
    }

    private TrueTypeFont builtIn(String name) throws IOException {
        try (InputStream in = plugin.getResource("fonts/" + name)) {
            if (in == null) {
                throw new IOException("missing bundled font " + name);
            }
            return new TTFParser().parse(new RandomAccessReadBuffer(in.readAllBytes()));
        }
    }

    private TrueTypeFont custom(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (!NAME.matcher(name).matches() || name.contains("..")) {
            plugin.getLogger().warning("pdf.yml font file name is not allowed: " + name);
            return null;
        }
        File dir = new File(plugin.getDataFolder(), "fonts");
        File f = new File(dir, name);
        try {
            if (!f.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()) || !f.isFile() || f.length() > MAX_SIZE) {
                plugin.getLogger().warning("pdf.yml font file not usable: " + name);
                return null;
            }
            return new TTFParser().parse(new RandomAccessReadBuffer(Files.readAllBytes(f.toPath())));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Cannot read font " + name + ", using built-in font", e);
            return null;
        }
    }

    /** 閒置太久就釋放。 */
    void releaseIfIdle() {
        if (regular != null && System.currentTimeMillis() - lastUse > IDLE_MS) {
            release();
        }
    }

    void release() {
        for (Face f : new Face[]{regular, bold}) {
            if (f == null) {
                continue;
            }
            close(f.primary);
            close(f.secondary);
        }
        regular = null;
        bold = null;
        loadedKey = "";
    }

    private static void close(TrueTypeFont t) {
        if (t != null) {
            try {
                t.close();
            } catch (IOException ignored) {
            }
        }
    }
}
