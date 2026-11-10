package sdlc.workflow;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

import sdlc.core.UsageException;

/**
 * Mind map of a test design: the feature at the root, test areas (## headings) as branches, test
 * scenarios (### TS-.. headings) as leaves. Rendered as a PNG (Confluence shows it on the page)
 * and as Mermaid source (renders on GitHub, or in Confluence with a Mermaid app).
 */
public final class MindMap {
    public record Node(String text, List<Node> children) {}

    private static final Color[] BRANCH = {new Color(0x534AB7), new Color(0x0F6E56), new Color(0x993C1D), new Color(0x185FA5), new Color(0x993556), new Color(0x854F0B), new Color(0x3B6D11)};

    private MindMap() {}

    public static Node parse(String md) {
        Node root = new Node("Feature", new ArrayList<>());
        Node branch = null;
        Pattern h = Pattern.compile("^(#{1,3})\\s+(.*)$");
        boolean inCode = false;
        String rootText = null;
        for (String line : md.split("\n")) {
            if (line.trim().startsWith("```")) inCode = !inCode;
            if (inCode) continue;
            Matcher m = h.matcher(line.trim());
            if (!m.matches() || !line.startsWith("#")) continue;
            int level = m.group(1).length();
            String text = m.group(2).trim();
            if (level == 1 && rootText == null) rootText = text;
            else if (level == 2 && !text.matches("(?i)(mind ?map|overview|scope|sources|traceability|open questions|assumptions|out of scope).*")) {
                branch = new Node(text, new ArrayList<>());
                root.children().add(branch);
            } else if (level == 3 && branch != null && text.matches("TS-[0-9]+.*")) {
                branch.children().add(new Node(text, new ArrayList<>()));
            }
        }
        root.children().removeIf(b -> b.children().isEmpty());
        return new Node(rootText == null ? "Feature" : rootText, root.children());
    }

    public static String mermaid(Node root) {
        StringBuilder sb = new StringBuilder("mindmap\n  root((" + clean(root.text()) + "))\n");
        for (Node b : root.children()) {
            sb.append("    ").append(clean(b.text())).append('\n');
            for (Node l : b.children()) sb.append("      ").append(clean(l.text())).append('\n');
        }
        return sb.toString();
    }

    private static String clean(String s) {
        return s.replaceAll("[()\\[\\]{}\"`]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Writes mindmap.png next to the draft and makes sure the draft shows it. */
    static String addImage(String md, Path draft) throws IOException {
        Node root = parse(md);
        if (root.children().isEmpty()) return md;
        Path png = draft.getParent().resolve("mindmap.png");
        render(root, png);
        StringBuilder out = new StringBuilder();
        if (!md.contains("](mindmap.png)")) {
            String[] parts = md.split("\n", 2);
            out.append(parts[0]).append("\n\n## Mind map\n\n![Mind map](mindmap.png)\n\n```mermaid\n").append(mermaid(root)).append("```\n\n");
            if (parts.length > 1) out.append(parts[1]);
            return out.toString();
        }
        return md;
    }

    static void render(Node root, Path out) throws IOException {
        System.setProperty("java.awt.headless", "true");
        Font rootFont = new Font(Font.SANS_SERIF, Font.BOLD, 16);
        Font branchFont = new Font(Font.SANS_SERIF, Font.BOLD, 13);
        Font leafFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        int leafRow = 26, gap = 14;
        int leaves = 0;
        for (Node b : root.children()) leaves += Math.max(1, b.children().size());
        int height = Math.max(200, leaves * leafRow + root.children().size() * gap + 60);
        int rootW = width(pg, rootFont, root.text(), 260) + 32, branchW = 0, leafW = 0;
        for (Node b : root.children()) {
            branchW = Math.max(branchW, width(pg, branchFont, b.text(), 240) + 28);
            for (Node l : b.children()) leafW = Math.max(leafW, width(pg, leafFont, l.text(), 420) + 24);
        }
        pg.dispose();
        int x0 = 30, x1 = x0 + rootW + 70, x2 = x1 + branchW + 60;
        int width = x2 + leafW + 30;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        int rootY = height / 2;
        int y = 30;
        int i = 0;
        for (Node b : root.children()) {
            Color c = BRANCH[i++ % BRANCH.length];
            int n = Math.max(1, b.children().size());
            int top = y;
            int by = top + n * leafRow / 2;
            g.setColor(c);
            g.setStroke(new BasicStroke(2f));
            g.draw(new CubicCurve2D.Double(x0 + rootW, rootY, x0 + rootW + 40, rootY, x1 - 40, by, x1, by));
            for (int k = 0; k < b.children().size(); k++) {
                int ly = top + k * leafRow + leafRow / 2;
                g.setStroke(new BasicStroke(1.2f));
                g.draw(new CubicCurve2D.Double(x1 + branchW, by, x1 + branchW + 30, by, x2 - 30, ly, x2, ly));
                box(g, x2, ly, leafW, 22, new Color(0xF4F4F2), c, leafFont, Color.DARK_GRAY, b.children().get(k).text());
            }
            box(g, x1, by, branchW, 28, lighter(c), c, branchFont, c.darker(), b.text());
            y += n * leafRow + gap;
        }
        box(g, x0, rootY, rootW, 40, new Color(0x2C2C2A), new Color(0x2C2C2A), rootFont, Color.WHITE, root.text());
        g.dispose();
        Files.createDirectories(out.getParent());
        ImageIO.write(img, "png", out.toFile());
    }

    private static int width(Graphics2D g, Font f, String text, int max) {
        return Math.min(max, g.getFontMetrics(f).stringWidth(text));
    }

    private static void box(Graphics2D g, int x, int cy, int w, int h, Color fill, Color line, Font f, Color textColor, String text) {
        RoundRectangle2D r = new RoundRectangle2D.Double(x, cy - h / 2.0, w, h, 10, 10);
        g.setColor(fill);
        g.fill(r);
        g.setColor(line);
        g.setStroke(new BasicStroke(1f));
        g.draw(r);
        g.setFont(f);
        g.setColor(textColor);
        FontMetrics fm = g.getFontMetrics();
        String t = text;
        while (fm.stringWidth(t) > w - 16 && t.length() > 4) t = t.substring(0, t.length() - 2);
        if (!t.equals(text)) t = t.substring(0, t.length() - 1) + "...";
        g.drawString(t, x + 8, cy + fm.getAscent() / 2 - 2);
    }

    private static Color lighter(Color c) {
        return new Color((c.getRed() + 255 * 4) / 5, (c.getGreen() + 255 * 4) / 5, (c.getBlue() + 255 * 4) / 5);
    }

    public static int cli(Path draft, Path out) throws IOException {
        Node root = parse(Files.readString(draft));
        if (root.children().isEmpty()) throw new UsageException("no '## area' headings with '### TS-..' scenarios found in " + draft);
        render(root, out);
        System.out.println("Mind map: " + out + "\n\n```mermaid\n" + mermaid(root) + "```");
        return 0;
    }
}
