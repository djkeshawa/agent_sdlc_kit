package sdlc.mandate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The card: the readable view of a mandate, as plain text for a terminal. It is rendered every
 * time and never stored, so there is no second text that looks approved.
 */
public final class MandateCard {
    private MandateCard() {}

    /** Lines are wrapped to this width; a single long path may run past it. */
    static final int WIDTH = 80;

    /**
     * Text as it may be printed: every control character (but not the line breaks the tool itself
     * writes), every invisible one and every one that turns the direction of text becomes "?".
     * A mandate's own text is checked when it is parsed; file names, names from git and the
     * approver's identity pass through here too.
     */
    public static String safe(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            if (Mandate.unprintable(c)) out.append('?');
            else out.appendCodePoint(c);
        });
        return out.toString();
    }

    public static String render(Mandate m, String blobId) {
        StringBuilder card = new StringBuilder();
        for (String line : draw(m, blobId).split("\n", -1)) card.append(safe(line)).append('\n');
        return card.substring(0, card.length() - 1);
    }

    private static String draw(Mandate m, String blobId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Mandate ").append(m.id()).append('\n');
        wrap(sb, m.intent(), "  ");
        if (m.featureKey() != null) sb.append("  Part of the feature ").append(m.featureKey()).append('\n');

        sb.append("\nMust stay true\n");
        if (m.mustStayTrue().isEmpty()) sb.append("  Nothing is listed.\n");
        for (Mandate.Truth t : m.mustStayTrue()) {
            wrap(sb, "- " + t.is(), "  ", "    ");
            sb.append("    kept by the test ").append(t.test()).append('\n');
        }

        sb.append("\nThis fails if\n");
        if (m.failsIf() == null) sb.append("  No one has written this sentence yet.\n");
        else wrap(sb, m.failsIf(), "  ");

        sb.append("\nMay change\n");
        for (String p : m.scopePaths()) sb.append("  ").append(p).append('\n');
        if (m.apis() != null) sb.append("  The mandate also limits the APIs. This version does not check that.\n");

        sb.append("\nMust pass\n");
        for (Mandate.Scenario s : m.scenarios()) {
            if (s.wholeFile()) sb.append("  every test in ").append(s.path()).append('\n');
            else sb.append("  ").append(s.name()).append(" in ").append(s.path()).append('\n');
        }

        sb.append("\nLocked tests (each must stay exactly as it is)\n");
        if (m.locked().isEmpty()) sb.append("  None.\n");
        for (Map.Entry<String, String> e : m.locked().entrySet()) {
            sb.append("  ").append(e.getKey()).append('\n');
            sb.append("    blob ").append(e.getValue()).append('\n');
        }

        sb.append("\nThis mandate is the file with blob ").append(blobId).append('\n');
        return sb.toString();
    }

    private static void wrap(StringBuilder sb, String text, String indent) {
        wrap(sb, text, indent, indent);
    }

    /** Word-wraps text to WIDTH: the first line after first, the others after rest. */
    private static void wrap(StringBuilder sb, String text, String first, String rest) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder(first);
        int start = first.length();
        for (String word : text.strip().split("\\s+")) {
            if (line.length() > start && line.length() + 1 + word.length() > WIDTH) {
                lines.add(line.toString());
                line = new StringBuilder(rest);
                start = rest.length();
            }
            if (line.length() > start) line.append(' ');
            line.append(word);
        }
        lines.add(line.toString());
        for (String l : lines) sb.append(l).append('\n');
    }
}
