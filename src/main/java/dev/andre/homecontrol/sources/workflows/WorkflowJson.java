package dev.andre.homecontrol.sources.workflows;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** Pure JSON selection for a single workflow response. */
public final class WorkflowJson {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build()).build();
    // IANA IPv6 Global Unicast Address Space, RIR-designated ALLOCATED rows (2025-10-10):
    // https://www.iana.org/assignments/ipv6-unicast-address-assignments
    private static final List<Ipv6Prefix> ALLOCATED_RIR_IPV6 = List.of(
            "2001:200::/23", "2001:400::/23", "2001:600::/23", "2001:800::/22",
            "2001:c00::/23", "2001:e00::/23", "2001:1200::/23", "2001:1400::/22",
            "2001:1800::/23", "2001:1a00::/23", "2001:1c00::/22", "2001:2000::/19",
            "2001:4000::/23", "2001:4200::/23", "2001:4400::/23", "2001:4600::/23",
            "2001:4800::/23", "2001:4a00::/23", "2001:4c00::/23", "2001:5000::/20",
            "2001:8000::/19", "2001:a000::/20", "2001:b000::/20", "2003::/18",
            "2400::/12", "2410::/12", "2600::/12", "2610::/23", "2620::/23",
            "2630::/12", "2800::/12", "2a00::/12", "2a10::/12", "2c00::/12"
    ).stream().map(Ipv6Prefix::parse).toList();

    private record Ipv6Prefix(int firstWord, int bits) {
        static Ipv6Prefix parse(String cidr) {
            String[] parts = cidr.split("/");
            return new Ipv6Prefix(ipv6FirstWord(InetAddress.ofLiteral(parts[0]).getAddress()),
                    Integer.parseInt(parts[1]));
        }

        boolean contains(int address) {
            int mask = -1 << (32 - bits);
            return (address & mask) == (firstWord & mask);
        }
    }

    private WorkflowJson() {}

    public record Entry(String key, String title, String subtitle, URI artwork, JsonNode node) {
        @Override public String toString() { return "Entry[key=" + key + "]"; }
    }

    public record Value(String text, boolean sensitive) {
        @Override public String toString() { return "Value[•••]"; }
    }

    public static JsonNode parse(byte[] body) {
        if (body == null || body.length > 2 * 1024 * 1024) fail(WorkflowException.Stage.PARSE, "invalid JSON response size");
        try (var parser = JSON.createParser(body)) {
            JsonNode root = JSON.readTree(parser);
            if (root == null || parser.nextToken() != null) fail(WorkflowException.Stage.PARSE, "invalid JSON response");
            return root;
        } catch (JacksonException e) {
            throw new WorkflowException(WorkflowException.Stage.PARSE, "invalid JSON response");
        }
    }

    public static List<Entry> entries(WorkflowDraft draft, JsonNode root) {
        if (draft.mode() == Mode.SINGLE) {
            Tile tile = draft.tile();
            return List.of(new Entry("single", tile.title(), tile.subtitle(), artwork(tile.artwork()), root));
        }
        Listing listing = draft.listing();
        JsonNode array = select(root, listing.arrayPointer());
        if (array == null || !array.isArray()) fail(WorkflowException.Stage.SELECT, "entry array is missing or invalid");
        if (array.size() > 200) fail(WorkflowException.Stage.SELECT, "too many entries");
        List<Entry> entries = new ArrayList<>(array.size());
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            String key;
            try {
                key = stableKey(select(node, listing.idPointer()));
            } catch (WorkflowException e) {
                throw new WorkflowException(WorkflowException.Stage.SELECT, "entry " + i + " has invalid ID");
            }
            if (!keys.add(key)) fail(WorkflowException.Stage.SELECT, "entry " + i + " has duplicate ID");
            JsonNode titleNode = select(node, listing.titlePointer());
            if (titleNode == null || !titleNode.isTextual() || titleNode.asText().isBlank()
                    || titleNode.asText().length() > 120) {
                fail(WorkflowException.Stage.SELECT, "entry " + i + " has invalid title");
            }
            JsonNode subtitleNode = listing.subtitlePointer() == null ? null : select(node, listing.subtitlePointer());
            String subtitle = subtitleNode != null && subtitleNode.isTextual()
                    && subtitleNode.asText().length() <= 240 ? subtitleNode.asText() : null;
            JsonNode artNode = listing.artworkPointer() == null ? null : select(node, listing.artworkPointer());
            URI art = artNode != null && artNode.isTextual() ? artwork(artNode.asText()) : null;
            entries.add(new Entry(key, titleNode.asText(), subtitle, art, node));
        }
        return List.copyOf(entries);
    }

    public static Map<String, Value> values(List<Variable> variables, JsonNode root, JsonNode entry) {
        Map<String, Value> values = new HashMap<>();
        for (Variable variable : variables) {
            JsonNode node = select(variable.scope() == Scope.ROOT ? root : entry, variable.pointer());
            if (node == null || node.isNull() || !(node.isTextual() || node.isNumber() || node.isBoolean())) {
                fail(WorkflowException.Stage.MAP, "mapping " + variable.name() + " has no scalar value");
            }
            values.put(variable.name(), new Value(node.isTextual() ? node.asText() : node.toString(), variable.sensitive()));
        }
        return Map.copyOf(values);
    }

    public static String stableKey(JsonNode id) {
        String typed;
        if (id != null && id.isTextual() && !id.asText().isEmpty()) {
            typed = "s:" + id.asText();
        } else if (id != null && id.isNumber()) {
            try {
                BigInteger integral = id.decimalValue().toBigIntegerExact();
                typed = "n:" + integral;
            } catch (ArithmeticException e) {
                throw new WorkflowException(WorkflowException.Stage.SELECT, "invalid entry ID");
            }
        } else {
            throw new WorkflowException(WorkflowException.Stage.SELECT, "invalid entry ID");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(typed.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static URI artwork(String raw) {
        if (raw == null) return null;
        try {
            URI uri = new URI(raw);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            String classifiedHost = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
            classifiedHost = classifiedHost.toLowerCase(java.util.Locale.ROOT);
            if (classifiedHost.equals("localhost") || classifiedHost.endsWith(".localhost")
                    || classifiedHost.endsWith(".local")) return null;
            for (String segment : uri.getPath().split("/", -1)) if (segment.equals(".") || segment.equals("..")) return null;
            if (classifiedHost.startsWith("[")) return publicIpv6(classifiedHost) ? uri : null;
            if (classifiedHost.matches("[0-9.]+")) return publicIpv4(classifiedHost) ? uri : null;
            if (!classifiedHost.contains(".")) return null;
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean publicIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return false;
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (octets[i] > 255 || (parts[i].length() > 1 && parts[i].charAt(0) == '0')) return false;
        }
        int a = octets[0], b = octets[1], c = octets[2];
        return a > 0 && a < 224 && a != 10 && a != 127
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && (b == 168 || (b == 0 && c == 0) || (b == 0 && c == 2)))
                && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                && !(a == 203 && b == 0 && c == 113);
    }

    private static boolean publicIpv6(String host) {
        // Literal parsing never resolves a hostname. Only published RIR allocations
        // are accepted; the embedded documentation block remains excluded.
        String literal = host.substring(1, host.length() - 1);
        try {
            InetAddress address = InetAddress.ofLiteral(literal);
            if (!(address instanceof Inet6Address) || literal.contains("%")) return false;
            byte[] bytes = address.getAddress();
            boolean documentation = (bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x01
                    && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8;
            int word = ipv6FirstWord(bytes);
            return !documentation && ALLOCATED_RIR_IPV6.stream().anyMatch(prefix -> prefix.contains(word));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int ipv6FirstWord(byte[] bytes) {
        return (bytes[0] & 255) << 24 | (bytes[1] & 255) << 16
                | (bytes[2] & 255) << 8 | (bytes[3] & 255);
    }

    private static JsonNode select(JsonNode root, String pointer) {
        return root == null ? null : root.at(pointer);
    }

    private static void fail(WorkflowException.Stage stage, String detail) {
        throw new WorkflowException(stage, detail);
    }
}
