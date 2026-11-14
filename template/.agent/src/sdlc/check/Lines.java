package sdlc.check;

import java.util.ArrayList;
import java.util.List;

/** The lines of two versions of a text that differ, as the places where one was changed into the other. */
final class Lines {
    private Lines() {}

    /** One changed place: lines [fromA, toA) of the old text became lines [fromB, toB) of the new one. Either range may be empty. */
    record Hunk(int fromA, int toA, int fromB, int toB) {
        boolean holdsOld(int line) {
            return line >= fromA && line < toA;
        }

        boolean holdsNew(int line) {
            return line >= fromB && line < toB;
        }
    }

    static String[] of(String text) {
        return text == null || text.isEmpty() ? new String[0] : text.split("\\R", -1);
    }

    /**
     * The changed places between two texts, by their longest common run of lines. Texts too large
     * to compare line by line are taken as one changed place between their common start and end:
     * more is then called changed than was, never less.
     */
    static List<Hunk> changed(String[] a, String[] b) {
        int start = 0;
        while (start < a.length && start < b.length && a[start].equals(b[start])) start++;
        int endA = a.length, endB = b.length;
        while (endA > start && endB > start && a[endA - 1].equals(b[endB - 1])) {
            endA--;
            endB--;
        }
        List<Hunk> out = new ArrayList<>();
        int n = endA - start, m = endB - start;
        if (n == 0 && m == 0) return out;
        if (n == 0 || m == 0 || (long) n * m > 4_000_000L) {
            out.add(new Hunk(start, endA, start, endB));
            return out;
        }
        // lcs[i][j]: the longest common run of lines of a[start+i..] and b[start+j..]
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[start + i].equals(b[start + j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0, j = 0, fromA = -1, fromB = -1;
        while (i < n || j < m) {
            if (i < n && j < m && a[start + i].equals(b[start + j])) {
                if (fromA >= 0) out.add(new Hunk(start + fromA, start + i, start + fromB, start + j));
                fromA = -1;
                i++;
                j++;
                continue;
            }
            if (fromA < 0) {
                fromA = i;
                fromB = j;
            }
            if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) j++;
            else i++;
        }
        if (fromA >= 0) out.add(new Hunk(start + fromA, start + n, start + fromB, start + m));
        return out;
    }

    /** The number of the line (from 0) that holds a place of the text. */
    static int at(String text, int place) {
        int line = 0;
        for (int k = 0; k < place && k < text.length(); k++) if (text.charAt(k) == '\n') line++;
        return line;
    }
}
