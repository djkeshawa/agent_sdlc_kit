package sdlc.check;

/** One thing the integrity scan found in a change. A PROBLEM fails the check; a NOTE is for the reader. */
public record Finding(Level level, String kind, String path, String detail) {
    public enum Level { PROBLEM, NOTE }

    static Finding problem(String kind, String path, String detail) {
        return new Finding(Level.PROBLEM, kind, path, detail);
    }

    static Finding note(String kind, String path, String detail) {
        return new Finding(Level.NOTE, kind, path, detail);
    }
}
