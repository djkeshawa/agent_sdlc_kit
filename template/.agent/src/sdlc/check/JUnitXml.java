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

public final class JUnitXml {
    private JUnitXml() {}

    public enum Status { PASSED, FAILED, ERRORED, SKIPPED }

    private static final long MAX_BYTES = 50L * 1024 * 1024;

    public record Case(String suite, String name, Status status, String type, String message, boolean retried) {
        public Case(String suite, String name, Status status, String type, String message) {
            this(suite, name, status, type, message, false);
        }
    }

    private static DocumentBuilder builder() throws javax.xml.parsers.ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder builder = f.newDocumentBuilder();
        builder.setErrorHandler(null);
        return builder;
    }

    // XML 1.0 attribute-value normalization: character references are read, CR LF and a lone CR become LF, then tab and LF become a space.
    public static final class Attributes {
        private final DocumentBuilder builder;

        public Attributes() {
            try {
                builder = builder();
            } catch (javax.xml.parsers.ParserConfigurationException e) {
                throw new IllegalStateException(e);
            }
        }

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
                boolean retried = false, last = false;
                for (Node n = c.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (!(n instanceof Element e)) continue;
                    switch (e.getTagName()) {
                        // Surefire rerunFailingTestsCount: a case that passed on a rerun has only flakyFailure or flakyError;
                        // one that failed every time has failure or error, then rerunFailure or rerunError.
                        case "flakyFailure", "flakyError" -> retried = true;
                        case "rerunFailure", "rerunError" -> {
                            retried = true;
                            if (!last) {
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
