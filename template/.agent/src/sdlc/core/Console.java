package sdlc.core;

/**
 * Where a workflow step writes for the person at the terminal. The steps do not print by
 * themselves: Main hands them the real console, a test hands them one that keeps the lines.
 */
public interface Console {
    /** A line of normal output. */
    void out(String line);

    /** A line that says something went wrong or needs attention. */
    void err(String line);

    /** The terminal: standard output and standard error. */
    Console TERMINAL = new Console() {
        public void out(String line) { System.out.println(line); }
        public void err(String line) { System.err.println(line); }
    };
}
