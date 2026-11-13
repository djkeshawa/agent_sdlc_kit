package sdlc.check;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    public enum Status { PASSED, FAILED, ERRORED, SKIPPED }

    /**
     * One test case of a report. FAILED means an assertion did not hold (a failure element);
     * ERRORED means something else was thrown (an error element). type is the exception's name.
     */
    public record Case(String suite, String name, Status status, String type, String message) {}

    public static List<Case> read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            var builder = f.newDocumentBuilder();
            builder.setErrorHandler(null); // no chatter on stderr; a bad report is an IOException below
            NodeList cases = builder.parse(in).getElementsByTagName("testcase");
            List<Case> out = new ArrayList<>();
            for (int i = 0; i < cases.getLength(); i++) {
                Element c = (Element) cases.item(i);
                Status status = Status.PASSED;
                String type = "", message = "";
                for (Node n = c.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (!(n instanceof Element e)) continue;
                    Status s = switch (e.getTagName()) {
                        case "failure" -> Status.FAILED;
                        case "error" -> Status.ERRORED;
                        case "skipped" -> Status.SKIPPED;
                        default -> null; // system-out, properties, rerunFailure, flakyFailure
                    };
                    if (s == null) continue;
                    status = s;
                    type = e.getAttribute("type");
                    message = e.getAttribute("message");
                    if (s != Status.SKIPPED) break;
                }
                String suite = c.getAttribute("classname");
                if (suite.isEmpty() && c.getParentNode() instanceof Element p) suite = p.getAttribute("name");
                out.add(new Case(suite, c.getAttribute("name"), status, type, message));
            }
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("not a JUnit XML report: " + file.getFileName() + " (" + e.getMessage() + ")");
        }
    }
}
