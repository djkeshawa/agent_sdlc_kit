package sdlc.check;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads JUnit XML reports (Maven Surefire, Vitest's junit reporter). The reports are written by
 * the code under test, so they are read as hostile input: no DTDs, no external entities.
 */
public final class JUnitXml {
    private JUnitXml() {}

    /** A case's last result. Whether the build ran it again is said apart (Case.retried). */
    public enum Status { PASSED, FAILED, ERRORED, SKIPPED }

    /** Reports are written by the code under test: one that is larger than this is not read. */
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    /**
     * One test case of a report. FAILED means an assertion did not hold (a failure element);
     * ERRORED means something else was thrown (an error element). type is the exception's name.
     * retried: the build ran the case again after it failed (Surefire's rerunFailingTestsCount).
     * The status is then the last one: PASSED where a rerun passed (flakyFailure, flakyError),
     * FAILED or ERRORED where every run failed (failure or error, then rerunFailure, rerunError).
     */
    public record Case(String suite, String name, Status status, String type, String message, boolean retried) {
        public Case(String suite, String name, Status status, String type, String message) {
            this(suite, name, status, type, message, false);
        }
    }

    /** The parser of every report, and of what a report would make of a name (see Attributes). */
    private static DocumentBuilder builder() throws javax.xml.parsers.ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder builder = f.newDocumentBuilder();
        builder.setErrorHandler(null); // no chatter on stderr; a bad report is an IOException
        return builder;
    }

    /**
     * Reads a text as read reads it where a report writes it as the value of an attribute: in a
     * UTF-8 document, between double quotes. The parser then reads its character references,
     * makes every line end one line feed (CR LF, a lone CR) and every tab and line feed a space
     * (XML 1.0, attribute-value normalization); nothing else. One parser for many texts.
     */
    public static final class Attributes {
        private final DocumentBuilder builder;

        public Attributes() {
            try {
                builder = builder();
            } catch (javax.xml.parsers.ParserConfigurationException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The value read, or null where the document is not well-formed XML (a report holding it cannot be read). Never throws. */
        public String read(String text) {
            try {
                builder.reset();
                builder.setErrorHandler(null);
                byte[] doc = ("<?xml version=\"1.0\" encoding=\"UTF-8\" ?><a v=\"" + text + "\"/>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return builder.parse(new java.io.ByteArrayInputStream(doc)).getDocumentElement().getAttribute("v");
            } catch (Exception e) {
                return null;
            }
        }
    }

    public static List<Case> read(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException("the report " + file.getFileName() + " is larger than " + (MAX_BYTES >> 20) + " MB");
        try (InputStream in = Files.newInputStream(file)) {
            NodeList cases = builder().parse(in).getElementsByTagName("testcase");
            List<Case> out = new ArrayList<>();
            for (int i = 0; i < cases.getLength(); i++) {
                Element c = (Element) cases.item(i);
                Status status = Status.PASSED;
                String type = "", message = "";
                boolean retried = false, last = false; // last: a failure, error or skipped element gave the status
                for (Node n = c.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (!(n instanceof Element e)) continue;
                    switch (e.getTagName()) {
                        // Surefire, rerunFailingTestsCount: a case that failed and passed when run again has only flakyFailure or flakyError elements,
                        // its result is a pass; one that failed every time has its failure or error, then a rerunFailure or rerunError for each rerun
                        case "flakyFailure", "flakyError" -> retried = true;
                        case "rerunFailure", "rerunError" -> {
                            retried = true;
                            if (!last) { // a rerun element without the failure before it: every run failed all the same
                                status = e.getTagName().equals("rerunFailure") ? Status.FAILED : Status.ERRORED;
                                type = e.getAttribute("type");
                                message = e.getAttribute("message");
                            }
                        }
                        case "failure", "error", "skipped" -> {
                            status = switch (e.getTagName()) {
                                case "failure" -> Status.FAILED;
                                case "error" -> Status.ERRORED;
                                default -> Status.SKIPPED;
                            };
                            type = e.getAttribute("type");
                            message = e.getAttribute("message");
                            last = true;
                        }
                        default -> {
                            // system-out, system-err, properties
                        }
                    }
                }
                String suite = c.getAttribute("classname");
                if (suite.isEmpty() && c.getParentNode() instanceof Element p) suite = p.getAttribute("name");
                out.add(new Case(suite, c.getAttribute("name"), status, type, message, retried));
            }
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("not a JUnit XML report: " + file.getFileName() + " (" + e.getMessage() + ")");
        }
    }
}
