package dev.andre.homecontrol.adapters.upnp.protocol;

import java.net.URI;
import java.util.Locale;

/** DIDL-Lite metadata for SetAVTransportURI. Built unescaped; SoapClient escapes it once more on the wire. */
public final class DidlLite {

    /** Byte-range seeking, not converted, DLNA 1.5 streaming flags (MiniDLNA's values). */
    public static final String DLNA_STREAMING = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";

    private static final String OPEN = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">";

    private DidlLite() {
    }

    public static String item(URI url, String contentFormat, String title, String artist, String additionalInfo) {
        StringBuilder xml = new StringBuilder(OPEN)
                .append("<item id=\"1\" parentID=\"0\" restricted=\"1\">")
                .append("<dc:title>").append(UpnpXml.escape(title == null || title.isBlank() ? "Home Control" : title)).append("</dc:title>");
        if (artist != null && !artist.isBlank()) {
            xml.append("<upnp:artist>").append(UpnpXml.escape(artist)).append("</upnp:artist>");
        }
        return xml.append("<upnp:class>").append(upnpClass(contentFormat)).append("</upnp:class>")
                .append("<res protocolInfo=\"").append(UpnpXml.escape("http-get:*:" + contentFormat + ":" + additionalInfo)).append("\">")
                .append(UpnpXml.escape(url.toString()))
                .append("</res></item></DIDL-Lite>")
                .toString();
    }

    public static String upnpClass(String contentFormat) {
        String type = contentFormat == null ? "" : contentFormat.strip().toLowerCase(Locale.ROOT);
        if (type.startsWith("audio/")) {
            return "object.item.audioItem.musicTrack";
        }
        if (type.startsWith("video/")) {
            return "object.item.videoItem.movie";
        }
        if (type.startsWith("image/")) {
            return "object.item.imageItem.photo";
        }
        return "object.item";
    }
}
