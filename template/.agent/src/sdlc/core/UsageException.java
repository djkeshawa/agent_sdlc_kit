package sdlc.core;

/** The person (or agent) asked for something the tool cannot do as asked; the message says what to do instead. */
public final class UsageException extends RuntimeException {
    public UsageException(String message) {
        super(message);
    }
}
