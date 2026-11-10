package clre20.aetherMart.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** PDFBox 繪圖小工具：多字型逐字選字、圓角矩形、圓、透明度、旋轉文字。座標原點在左下角（同 PDF）。 */
final class PdfCanvas implements AutoCloseable {

    static final float W = PDRectangle.A4.getWidth();
    static final float H = PDRectangle.A4.getHeight();
    private static final float K = 0.5523f;

    /** 同一字重下的兩個字型（主、備援）。 */
    record FontPair(PDType0Font primary, PDType0Font secondary, PdfFonts.Face face) {
    }

    private record Run(PDType0Font font, String text) {
    }

    final PDDocument doc;
    final FontPair regular;
    final FontPair bold;
    final List<PDPage> pages = new ArrayList<>();
    PDPageContentStream cs;

    PdfCanvas(PDDocument doc, PdfFonts fonts) throws IOException {
        this.doc = doc;
        this.regular = pair(doc, fonts.regular());
        this.bold = pair(doc, fonts.bold());
    }

    private static FontPair pair(PDDocument doc, PdfFonts.Face f) throws IOException {
        PDType0Font p = PDType0Font.load(doc, f.primary, true);
        PDType0Font s = f.secondary == null ? null : PDType0Font.load(doc, f.secondary, true);
        return new FontPair(p, s, f);
    }

    void newPage() throws IOException {
        if (cs != null) {
            cs.close();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        pages.add(page);
        cs = new PDPageContentStream(doc, page);
    }

    /** 重新打開某一頁（畫頁尾用）。 */
    void reopen(PDPage page) throws IOException {
        if (cs != null) {
            cs.close();
        }
        cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true);
    }

    @Override
    public void close() throws IOException {
        if (cs != null) {
            cs.close();
            cs = null;
        }
    }

    // ---------------- 文字 ----------------

    private List<Run> runs(String text, FontPair fp) {
        List<Run> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder sb = new StringBuilder();
        PDType0Font cur = null;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp)) {
                continue;
            }
            int w = fp.face().which(cp);
            PDType0Font f;
            String ch;
            if (w == 0) {
                f = fp.primary();
                ch = new String(Character.toChars(cp));
            } else if (w == 1 && fp.secondary() != null) {
                f = fp.secondary();
                ch = new String(Character.toChars(cp));
            } else {
                int box = '□';
                if (fp.face().which(box) == 0) {
                    f = fp.primary();
                    ch = "□";
                } else {
                    f = fp.primary();
                    ch = "?";
                }
            }
            if (cur != null && f != cur) {
                out.add(new Run(cur, sb.toString()));
                sb.setLength(0);
            }
            cur = f;
            sb.append(ch);
        }
        if (cur != null && !sb.isEmpty()) {
            out.add(new Run(cur, sb.toString()));
        }
        return out;
    }

    float width(String text, boolean isBold, float size) {
        float w = 0;
        for (Run r : runs(text, isBold ? bold : regular)) {
            try {
                w += r.font().getStringWidth(r.text()) / 1000f * size;
            } catch (IOException | IllegalArgumentException ignored) {
            }
        }
        return w;
    }

    /** 太寬時截斷並加「…」。 */
    String fit(String text, boolean isBold, float size, float max) {
        if (text == null) {
            return "";
        }
        if (width(text, isBold, size) <= max) {
            return text;
        }
        String t = text;
        while (!t.isEmpty() && width(t + "…", isBold, size) > max) {
            t = t.substring(0, t.offsetByCodePoints(t.length(), -1));
        }
        return t + "…";
    }

    void text(String text, float x, float y, boolean isBold, float size, Color color) throws IOException {
        List<Run> rs = runs(text, isBold ? bold : regular);
        if (rs.isEmpty()) {
            return;
        }
        cs.setNonStrokingColor(color);
        cs.beginText();
        cs.newLineAtOffset(x, y);
        for (Run r : rs) {
            cs.setFont(r.font(), size);
            cs.showText(r.text());
        }
        cs.endText();
    }

    void textRight(String text, float right, float y, boolean isBold, float size, Color color) throws IOException {
        text(text, right - width(text, isBold, size), y, isBold, size, color);
    }

    void textCenter(String text, float cx, float y, boolean isBold, float size, Color color) throws IOException {
        text(text, cx - width(text, isBold, size) / 2, y, isBold, size, color);
    }

    /** 以 (cx, cy) 為中心、旋轉 angle（弧度）畫一個字（印章弧形文字用）。 */
    void rotatedCenter(String text, float cx, float cy, double angle, boolean isBold, float size, Color color) throws IOException {
        List<Run> rs = runs(text, isBold ? bold : regular);
        if (rs.isEmpty()) {
            return;
        }
        float w = width(text, isBold, size);
        cs.setNonStrokingColor(color);
        cs.beginText();
        Matrix m = Matrix.getRotateInstance(angle, cx, cy);
        m.translate(-w / 2, -size * 0.35f);
        cs.setTextMatrix(m);
        for (Run r : rs) {
            cs.setFont(r.font(), size);
            cs.showText(r.text());
        }
        cs.endText();
    }

    // ---------------- 圖形 ----------------

    void fillRect(float x, float y, float w, float h, Color c) throws IOException {
        cs.setNonStrokingColor(c);
        cs.addRect(x, y, w, h);
        cs.fill();
    }

    void line(float x1, float y1, float x2, float y2, float width, Color c) throws IOException {
        cs.setStrokingColor(c);
        cs.setLineWidth(width);
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    private void roundPath(float x, float y, float w, float h, float r) throws IOException {
        float k = K * r;
        cs.moveTo(x + r, y);
        cs.lineTo(x + w - r, y);
        cs.curveTo(x + w - r + k, y, x + w, y + r - k, x + w, y + r);
        cs.lineTo(x + w, y + h - r);
        cs.curveTo(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h);
        cs.lineTo(x + r, y + h);
        cs.curveTo(x + r - k, y + h, x, y + h - r + k, x, y + h - r);
        cs.lineTo(x, y + r);
        cs.curveTo(x, y + r - k, x + r - k, y, x + r, y);
        cs.closePath();
    }

    void roundRect(float x, float y, float w, float h, float r, Color fill, Color stroke, float lineWidth) throws IOException {
        roundPath(x, y, w, h, r);
        if (fill != null && stroke != null) {
            cs.setNonStrokingColor(fill);
            cs.setStrokingColor(stroke);
            cs.setLineWidth(lineWidth);
            cs.fillAndStroke();
        } else if (fill != null) {
            cs.setNonStrokingColor(fill);
            cs.fill();
        } else if (stroke != null) {
            cs.setStrokingColor(stroke);
            cs.setLineWidth(lineWidth);
            cs.stroke();
        }
    }

    private void circlePath(float cx, float cy, float r) throws IOException {
        float k = K * r;
        cs.moveTo(cx + r, cy);
        cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
        cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
        cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
        cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
        cs.closePath();
    }

    void circle(float cx, float cy, float r, Color fill, Color stroke, float lineWidth) throws IOException {
        circlePath(cx, cy, r);
        if (fill != null) {
            cs.setNonStrokingColor(fill);
            cs.fill();
        } else {
            cs.setStrokingColor(stroke);
            cs.setLineWidth(lineWidth);
            cs.stroke();
        }
    }

    void alpha(float a) throws IOException {
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.setStrokingAlphaConstant(a);
        gs.setNonStrokingAlphaConstant(a);
        cs.setGraphicsStateParameters(gs);
    }

    void save() throws IOException {
        cs.saveGraphicsState();
    }

    void restore() throws IOException {
        cs.restoreGraphicsState();
    }

    void rotateAround(double angle, float cx, float cy) throws IOException {
        cs.transform(Matrix.getTranslateInstance(cx, cy));
        cs.transform(Matrix.getRotateInstance(angle, 0, 0));
        cs.transform(Matrix.getTranslateInstance(-cx, -cy));
    }
}
