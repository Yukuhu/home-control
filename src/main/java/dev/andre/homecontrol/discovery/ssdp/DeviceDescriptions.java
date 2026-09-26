package dev.andre.homecontrol.discovery.ssdp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Parses UPnP device description XML fetched from an SSDP {@code LOCATION}. Device-supplied, so no DTDs. */
public final class DeviceDescriptions {

    private DeviceDescriptions() {
    }

    public static DeviceDescription parse(byte[] xml, URI location) {
        try {
            Document document = documentBuilder().parse(new ByteArrayInputStream(xml));
            Element root = document.getDocumentElement();
            String urlBase = childText(root, "URLBase");
            URI base = urlBase != null ? URI.create(urlBase) : location;
            Element device = firstChild(root, "device");
            if (device == null) {
                throw new IllegalArgumentException("No <device> element in the description at " + location);
            }
            List<DeviceDescription.Service> services = new ArrayList<>();
            NodeList serviceNodes = document.getElementsByTagName("service");
            for (int i = 0; i < serviceNodes.getLength(); i++) {
                Element service = (Element) serviceNodes.item(i);
                services.add(new DeviceDescription.Service(
                        childText(service, "serviceType"),
                        childText(service, "serviceId"),
                        resolve(base, childText(service, "controlURL")),
                        resolve(base, childText(service, "eventSubURL")),
                        resolve(base, childText(service, "SCPDURL"))));
            }
            return new DeviceDescription(childText(device, "friendlyName"), childText(device, "manufacturer"),
                    childText(device, "modelName"), childText(device, "UDN"), services);
        } catch (ParserConfigurationException | SAXException | IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Unreadable device description at " + location + ": " + e.getMessage(), e);
        }
    }

    /**
     * The one hardened parser for device-supplied XML (descriptions, SCPDs, SOAP answers): no DTDs,
     * no external entities or schemas, no XInclude, prefixes kept as written, and nothing printed
     * to stderr on a malformed document (fatal errors still throw).
     */
    public static DocumentBuilder documentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        return builder;
    }

    private static Element firstChild(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    private static String localName(Element element) {
        String tag = element.getTagName();
        int colon = tag.indexOf(':');
        return colon < 0 ? tag : tag.substring(colon + 1);
    }

    private static String childText(Element parent, String name) {
        Element child = firstChild(parent, name);
        if (child == null) {
            return null;
        }
        String text = child.getTextContent().trim();
        return text.isEmpty() ? null : text;
    }

    private static URI resolve(URI base, String value) {
        if (value == null) {
            return null;
        }
        try {
            return base == null ? URI.create(value) : base.resolve(value);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
