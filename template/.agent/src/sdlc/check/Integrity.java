package sdlc.check;

import java.util.ArrayList;
import java.util.List;

import sdlc.workflow.Checks;

/**
 * What a change does to the things that are supposed to check it: tests switched off or removed,
 * secrets, and changes to the build, the pipeline, dependencies and migrations. Read from the
 * diff alone; nothing is run.
 */
final class Integrity {
    private Integrity() {}

    static List<Finding> scan(Change change) {
        List<Finding> out = new ArrayList<>();
        var added = change.addedLines();
        for (String hit : Checks.disabledTests(added)) out.add(Finding.problem("test switched off", before(hit), after(hit)));
        for (String hit : Checks.secrets(added)) out.add(Finding.problem("possible secret", before(hit), after(hit)));
        return out;
    }

    private static String before(String hit) {
        int at = hit.indexOf(": ");
        return at < 0 ? hit : hit.substring(0, at);
    }

    private static String after(String hit) {
        int at = hit.indexOf(": ");
        return at < 0 ? "" : hit.substring(at + 2);
    }
}
