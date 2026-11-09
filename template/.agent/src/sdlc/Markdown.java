package sdlc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown <-> Confluence storage format. Covers what specs need: headings, paragraphs, nested
 * lists, task boxes, tables, code blocks (Mermaid too), block quotes, links, images (as
 * attachments), bold, italics and inline code.
 */
final class Markdown {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern ITEM = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$");
    private static final Pattern IMAGE = Pattern.compile("^!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)$");
    private static final Set<String> CODE_LANGS = Set.of("java", "javascript", "js", "typescript", "ts", "sql", "xml", "html", "bash", "sh", "shell", "json", "yaml", "yml", "python", "groovy", "kotlin", "css", "text");

    private Markdown() {}

    /** Storage XHTML plus the local image files it references (to upload as attachments). */
    record Converted(String storage, List<String> images) {}

    static Converted toStorage(String md) {
        StringBuilder out = new StringBuilder();
        List<String> images = new ArrayList<>();
        String[] lines = md.replace("\r\n", "\n").split("\n", -1);
        List<String> para = new ArrayList<>();
        Lists lists = new Lists(out);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            if (t.startsWith("```")) {
                flush(out, para);
                lists.closeAll();
                String lang = t.substring(3).trim().toLowerCase();
                StringBuilder code = new StringBuilder();
                while (++i < lines.length && !lines[i].trim().startsWith("```")) code.append(lines[i]).append('\n');
                out.append(codeMacro(lang, code.toString()));
                continue;
            }
            if (t.isEmpty()) {
                flush(out, para);
                lists.closeAll();
                continue;
            }
            Matcher h = HEADING.matcher(t);
            if (h.matches() && line.startsWith("#")) {
                flush(out, para);
                lists.closeAll();
                int n = h.group(1).length();
                out.append("<h").append(n).append('>').append(inline(h.group(2))).append("</h").append(n).append('>');
                continue;
            }
            if (t.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                flush(out, para);
                lists.closeAll();
                out.append("<hr/>");
                continue;
            }
            Matcher img = IMAGE.matcher(t);
            if (img.matches()) {
                flush(out, para);
                lists.closeAll();
                String src = img.group(2);
                if (src.startsWith("http")) out.append("<p><ac:image><ri:url ri:value=\"").append(escape(src)).append("\"/></ac:image></p>");
                else {
                    String name = src.substring(src.replace('\\', '/').lastIndexOf('/') + 1);
                    images.add(src);
                    out.append("<p><ac:image ac:alt=\"").append(escape(img.group(1))).append("\"><ri:attachment ri:filename=\"").append(escape(name)).append("\"/></ac:image></p>");
                }
                continue;
            }
            if (t.startsWith("|")) {
                flush(out, para);
                lists.closeAll();
                List<String> rows = new ArrayList<>();
                while (i < lines.length && lines[i].trim().startsWith("|")) rows.add(lines[i++].trim());
                i--;
                out.append(table(rows));
                continue;
            }
            if (t.startsWith(">")) {
                flush(out, para);
                lists.closeAll();
                out.append("<blockquote><p>").append(inline(t.replaceFirst("^>\\s?", ""))).append("</p></blockquote>");
                continue;
            }
            Matcher item = ITEM.matcher(line);
            if (item.matches()) {
                flush(out, para);
                lists.item(item.group(1).length(), item.group(2).matches("\\d+[.)]") ? "ol" : "ul", item.group(3));
                continue;
            }
            if (lists.open() && line.startsWith("  ")) {
                lists.continueItem(t); // a wrapped list item line
                continue;
            }
            lists.closeAll();
            para.add(t);
        }
        flush(out, para);
        lists.closeAll();
        return new Converted(out.toString(), images);
    }

    /** Nested lists by indentation. */
    private static final class Lists {
        private record Level(int indent, String kind) {}

        private final StringBuilder out;
        private final Deque<Level> stack = new ArrayDeque<>();

        Lists(StringBuilder out) {
            this.out = out;
        }

        boolean open() {
            return !stack.isEmpty();
        }

        void item(int indent, String kind, String text) {
            while (!stack.isEmpty() && stack.peek().indent > indent) {
                out.append("</li></").append(stack.pop().kind).append('>');
            }
            if (!stack.isEmpty() && stack.peek().indent == indent && !stack.peek().kind.equals(kind)) {
                out.append("</li></").append(stack.pop().kind).append('>');
            }
            if (stack.isEmpty() || stack.peek().indent < indent) {
                out.append('<').append(kind).append('>');
                stack.push(new Level(indent, kind));
            } else {
                out.append("</li>");
            }
            String box = "";
            if (text.startsWith("[ ] ") || text.startsWith("[x] ") || text.startsWith("[X] ")) {
                box = text.charAt(1) == ' ' ? "&#9744; " : "&#9745; ";
                text = text.substring(4);
            }
            out.append("<li>").append(box).append(inline(text));
        }

        void continueItem(String text) {
            out.append(' ').append(inline(text));
        }

        void closeAll() {
            while (!stack.isEmpty()) out.append("</li></").append(stack.pop().kind).append('>');
        }
    }

    private static String codeMacro(String lang, String code) {
        String cdata = "<![CDATA[" + code.replace("]]>", "]]]]><![CDATA[>") + "]]>";
        String mermaidMacro = Ctx.cfg == null ? "" : Ctx.cfg.str("confluence.mermaidMacro");
        if (lang.equals("mermaid") && !mermaidMacro.isBlank()) {
            return "<ac:structured-macro ac:name=\"" + escape(mermaidMacro) + "\"><ac:plain-text-body>" + cdata + "</ac:plain-text-body></ac:structured-macro>";
        }
        String param = CODE_LANGS.contains(lang) ? "<ac:parameter ac:name=\"language\">" + escape(lang.equals("yml") ? "yaml" : lang) + "</ac:parameter>" : "";
        String title = lang.equals("mermaid") ? "<ac:parameter ac:name=\"title\">Mermaid diagram (paste into a Mermaid viewer)</ac:parameter>" : "";
        return "<ac:structured-macro ac:name=\"code\">" + param + title + "<ac:plain-text-body>" + cdata + "</ac:plain-text-body></ac:structured-macro>";
    }

    private static void flush(StringBuilder out, List<String> para) {
        if (para.isEmpty()) return;
        out.append("<p>").append(inline(String.join(" ", para))).append("</p>");
        para.clear();
    }

    private static String table(List<String> rows) {
        StringBuilder sb = new StringBuilder("<table><tbody>");
        boolean header = rows.size() > 1 && rows.get(1).matches("^\\|?\\s*:?-{2,}.*");
        for (int r = 0; r < rows.size(); r++) {
            if (header && r == 1) continue;
            String row = rows.get(r).replaceAll("^\\|", "").replaceAll("\\|$", "");
            String cell = header && r == 0 ? "th" : "td";
            sb.append("<tr>");
            for (String c : row.split("(?<!\\\\)\\|", -1)) sb.append('<').append(cell).append('>').append(inline(c.trim().replace("\\|", "|"))).append("</").append(cell).append('>');
            sb.append("</tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    static String inline(String text) {
        List<String> code = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(escape(text));
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            code.add(m.group(1));
            m.appendReplacement(sb, "\u0000" + (code.size() - 1) + "\u0000");
        }
        m.appendTail(sb);
        String s = sb.toString();
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("(?<![\\w*])\\*([^*\\s][^*]*)\\*(?![\\w*])", "<em>$1</em>");
        s = s.replaceAll("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)", "<a href=\"$2\">$1</a>");
        for (int i = 0; i < code.size(); i++) s = s.replace("\u0000" + i + "\u0000", "<code>" + code.get(i) + "</code>");
        return s;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------------- storage -> Markdown (for agents to read pulled pages) ----------------

    private static final Pattern CODE_BLOCK = Pattern.compile("<ac:structured-macro[^>]*ac:name=\"(code|noformat|[a-z-]*mermaid[a-z-]*)\"[^>]*>(.*?)</ac:structured-macro>", Pattern.DOTALL);
    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL);
    private static final Pattern LANG = Pattern.compile("<ac:parameter ac:name=\"language\">([^<]*)</ac:parameter>");

    /** Code blocks of a page: [language, content]. */
    static List<String[]> codeBlocks(String storage) {
        List<String[]> out = new ArrayList<>();
        Matcher m = CODE_BLOCK.matcher(storage);
        while (m.find()) {
            StringBuilder body = new StringBuilder();
            Matcher c = CDATA.matcher(m.group(2));
            while (c.find()) body.append(c.group(1));
            Matcher l = LANG.matcher(m.group(2));
            out.add(new String[] {m.group(1).contains("mermaid") ? "mermaid" : l.find() ? l.group(1) : "", body.toString().replace("]]]]><![CDATA[>", "]]>")});
        }
        return out;
    }

    static String toMarkdown(String storage) {
        List<String> blocks = new ArrayList<>();
        Matcher m = CODE_BLOCK.matcher(storage);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            List<String[]> one = codeBlocks(m.group());
            String[] b = one.isEmpty() ? new String[] {"", ""} : one.get(0);
            blocks.add("\n```" + b[0] + "\n" + b[1] + (b[1].endsWith("\n") ? "" : "\n") + "```\n");
            m.appendReplacement(sb, Matcher.quoteReplacement("\u0001" + (blocks.size() - 1) + "\u0001"));
        }
        m.appendTail(sb);
        String s = confluenceBlocks(sb.toString());
        s = s.replaceAll("(?s)<ac:structured-macro[^>]*ac:name=\"(toc|children|anchor)\".*?</ac:structured-macro>", "");
        s = s.replaceAll("<ac:image[^>]*>\\s*<ri:attachment ri:filename=\"([^\"]+)\"\\s*/>\\s*</ac:image>", "[image: $1]");
        s = s.replaceAll("<ri:user[^>]*/>", "@user");
        s = s.replaceAll("<h([1-6])[^>]*>", "\n\n\u0002$1 ").replaceAll("</h[1-6]>", "\n");
        s = s.replaceAll("<tr[^>]*>", "\n|").replaceAll("</t[hd]>", " |").replaceAll("<t[hd][^>]*>", " ");
        s = s.replaceAll("<li[^>]*>", "\n- ").replaceAll("<(p|br|div)[^>]*/?>", "\n");
        s = s.replaceAll("<(strong|b)>", "**").replaceAll("</(strong|b)>", "**").replaceAll("<code>", "`").replaceAll("</code>", "`");
        s = s.replaceAll("<a [^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", "[$2]($1)");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&nbsp;", " ").replace("&#9744;", "[ ]").replace("&#9745;", "[x]").replace("&amp;", "&");
        Matcher hd = Pattern.compile("\u0002([1-6]) ").matcher(s);
        StringBuilder hs = new StringBuilder();
        while (hd.find()) hd.appendReplacement(hs, "#".repeat(Integer.parseInt(hd.group(1))) + " ");
        hd.appendTail(hs);
        s = hs.toString();
        s = s.replace("\u0005", "\n");
        for (int i = 0; i < blocks.size(); i++) s = s.replace("\u0001" + i + "\u0001", blocks.get(i));
        return s.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim() + "\n";
    }

    private static final Pattern STATUS = Pattern.compile("(?s)<ac:structured-macro[^>]*ac:name=\"status\"[^>]*>(.*?)</ac:structured-macro>");
    private static final Pattern TITLE_PARAM = Pattern.compile("<ac:parameter ac:name=\"title\">([^<]*)</ac:parameter>");
    private static final Pattern PANEL = Pattern.compile("(?s)<ac:structured-macro[^>]*ac:name=\"(info|note|warning|tip|panel|success|error)\"[^>]*>(.*?)<ac:rich-text-body>(.*?)</ac:rich-text-body>\\s*</ac:structured-macro>");
    private static final Pattern EXPAND = Pattern.compile("(?s)<ac:structured-macro[^>]*ac:name=\"expand\"[^>]*>(.*?)<ac:rich-text-body>(.*?)</ac:rich-text-body>\\s*</ac:structured-macro>");
    private static final Pattern ADF_PANEL = Pattern.compile("(?s)<ac:adf-node type=\"panel\">(.*?)<ac:adf-content>(.*?)</ac:adf-content>\\s*</ac:adf-node>");
    private static final Pattern ADF_DECISION = Pattern.compile("(?s)<ac:adf-node type=\"decision-item\">(.*?)<ac:adf-content>(.*?)</ac:adf-content>\\s*</ac:adf-node>");
    private static final Pattern ADF_ATTR = Pattern.compile("<ac:adf-attribute key=\"([^\"]+)\">([^<]*)</ac:adf-attribute>");
    private static final Pattern TASK = Pattern.compile("(?s)<ac:task>.*?<ac:task-status>(\\w+)</ac:task-status>.*?<ac:task-body>(.*?)</ac:task-body>.*?</ac:task>");
    private static final Pattern ORDERED = Pattern.compile("(?s)<ol[^>]*>(.*?)</ol>");
    private static final Pattern TABLE = Pattern.compile("(?s)<table[^>]*>(.*?)</table>");
    private static final Pattern ROW = Pattern.compile("(?s)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?s)<t([hd])[^>]*>(.*?)</t[hd]>");

    /**
     * Confluence-specific blocks, so agents read the content and none of the editor's bookkeeping:
     * status lozenges, panels, expands, ADF panels and decisions (without their duplicate fallback
     * copy), task lists, numbered lists and tables whose cells hold paragraphs.
     */
    static String confluenceBlocks(String s) {
        s = s.replaceAll("(?s)<ac:adf-fallback>.*?</ac:adf-fallback>", "");
        s = each(s, STATUS, mt -> {
            Matcher t = TITLE_PARAM.matcher(mt.group(1));
            return t.find() ? "[" + t.group(1) + "]" : "";
        });
        s = each(s, PANEL, mt -> "<p><strong>" + label(mt.group(1)) + ":</strong></p>" + mt.group(3));
        s = each(s, ADF_PANEL, mt -> {
            String type = "note";
            Matcher a = ADF_ATTR.matcher(mt.group(1));
            while (a.find()) if (a.group(1).equals("panel-type")) type = a.group(2);
            return "<p><strong>" + label(type) + ":</strong></p>" + mt.group(2);
        });
        s = each(s, EXPAND, mt -> {
            Matcher t = TITLE_PARAM.matcher(mt.group(1));
            return "<p><strong>" + (t.find() ? t.group(1) : "Details") + "</strong></p>" + mt.group(2);
        });
        s = each(s, ADF_DECISION, mt -> {
            String state = "";
            Matcher a = ADF_ATTR.matcher(mt.group(1));
            while (a.find()) if (a.group(1).equals("state")) state = a.group(2);
            return "<li>Decision" + (state.isEmpty() ? "" : " (" + state.toLowerCase(java.util.Locale.ROOT) + ")") + ": " + mt.group(2) + "</li>";
        });
        s = s.replaceAll("<ac:adf-node type=\"decision-list\">", "<ul>").replaceAll("</ac:adf-node>", "</ul>");
        s = s.replaceAll("<ac:adf-attribute[^>]*>[^<]*</ac:adf-attribute>", "").replaceAll("</?ac:adf-(extension|content)>", "");
        s = each(s, TASK, mt -> "<li>" + (mt.group(1).equals("complete") ? "[x] " : "[ ] ") + mt.group(2) + "</li>");
        s = s.replaceAll("<ac:task-list[^>]*>", "<ul>").replaceAll("</ac:task-list>", "</ul>");
        s = s.replaceAll("(?s)<ac:parameter[^>]*>.*?</ac:parameter>", ""); // leftover macro settings (colours, layout ids)
        s = s.replaceAll("<li([^>]*)>\\s*<p[^>]*>", "<li$1>").replaceAll("</p>\\s*</li>", "</li>");
        s = each(s, ORDERED, mt -> {
            int[] n = {0};
            String items = mt.group(1).replaceAll("</li>", "");
            return "<p></p>" + Pattern.compile("<li[^>]*>").matcher(items).replaceAll(r -> "\u0005" + (++n[0]) + ". ") + "<p></p>";
        });
        return each(s, TABLE, mt -> {
            StringBuilder t = new StringBuilder("\u0005\u0005");
            Matcher row = ROW.matcher(mt.group(1));
            boolean first = true;
            while (row.find()) {
                List<String> cells = new ArrayList<>();
                Matcher c = CELL.matcher(row.group(1));
                while (c.find()) cells.add(cellText(c.group(2)));
                if (cells.isEmpty()) continue;
                t.append("| ").append(String.join(" | ", cells)).append(" |\u0005");
                if (first) t.append("|").append(" --- |".repeat(cells.size())).append("\u0005");
                first = false;
            }
            return t.append("\u0005").toString();
        });
    }

    private static String label(String type) {
        return switch (type) {
            case "warning", "error" -> "Warning";
            case "tip", "success" -> "Tip";
            case "info" -> "Info";
            default -> "Note";
        };
    }

    /** A table cell on one line: inline formatting kept, paragraphs joined, pipes escaped. */
    private static String cellText(String html) {
        String c = html.replaceAll("<(strong|b)>", "**").replaceAll("</(strong|b)>", "**").replaceAll("<code>", "`").replaceAll("</code>", "`");
        c = c.replaceAll("<a [^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", "[$2]($1)").replaceAll("<(br|/p|/li)[^>]*>", " ").replaceAll("<[^>]+>", "");
        return c.replaceAll("\\s+", " ").trim().replace("|", "\\|");
    }

    private static String each(String s, Pattern p, java.util.function.Function<Matcher, String> f) {
        Matcher mt = p.matcher(s);
        StringBuilder out = new StringBuilder();
        while (mt.find()) mt.appendReplacement(out, Matcher.quoteReplacement(f.apply(mt)));
        mt.appendTail(out);
        return out.toString();
    }
}
