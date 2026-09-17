package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.discovery.ssdp.DeviceDescriptions;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Device-supplied XML, parsed defensively: no DTDs, no external entities, prefixes ignored. */
public final class UpnpXml {

    private UpnpXml() {
    }

    public static Element parse(byte[] xml) {
        if (xml == null || xml.length == 0) {
            throw new IllegalArgumentException("Unreadable XML: empty document");
        }
        try {
            // The same hardened parser as device descriptions: no DTDs, no external entities, silent on errors.
            return DeviceDescriptions.documentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("Unreadable XML: " + e.getMessage(), e);
        }
    }

    public static Element parse(String xml) {
        return parse(xml == null ? new byte[0] : xml.getBytes(StandardCharsets.UTF_8));
    }

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    public static String localName(Node node) {
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    public static List<Element> childElements(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }

    /** Trimmed text of the first child with that local name; blank counts as absent. */
    public static Optional<String> childText(Element parent, String localName) {
        return childElements(parent).stream()
                .filter(child -> localName.equals(localName(child)))
                .map(child -> child.getTextContent().trim())
                .filter(text -> !text.isEmpty())
                .findFirst();
    }

    /** Every element below {@code root} (not root itself) with that local name, in document order. */
    public static List<Element> descendants(Element root, String localName) {
        List<Element> found = new ArrayList<>();
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (localName.equals(localName(element))) {
                found.add(element);
            }
        }
        return found;
    }

    /** {@code root} itself if it has that local name, else its first such descendant. */
    public static Optional<Element> firstDescendant(Element root, String localName) {
        if (localName.equals(localName(root))) {
            return Optional.of(root);
        }
        return descendants(root, localName).stream().findFirst();
    }
}
