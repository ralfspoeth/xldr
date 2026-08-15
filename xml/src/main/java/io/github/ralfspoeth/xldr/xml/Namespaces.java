package io.github.ralfspoeth.xldr.xml;

import org.jspecify.annotations.Nullable;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Prefix to namespace bindings for the XPath expressions of one input.
 * <p>
 * XPath 1.0 has no notion of a default namespace: an unprefixed name in an
 * expression matches elements in <em>no</em> namespace, whatever the document
 * declares. So a document with a default namespace can only be addressed by
 * binding a prefix here and using it in the selectors, even if the document
 * itself uses none.
 * <p>
 * Bindings come from the adapter properties, one per {@code ns.} key:
 *
 * <pre>
 * ns.f = http://example.com/funds
 * </pre>
 *
 * which makes {@code //f:fund} match.
 */
final class Namespaces implements NamespaceContext {

    static final String PREFIX = "ns.";

    private final Map<String, String> byPrefix = new HashMap<>();

    private Namespaces() {}

    static Namespaces of(Map<String, String> properties) {
        var namespaces = new Namespaces();
        properties.forEach((name, value) -> {
            if (name.startsWith(PREFIX) && name.length() > PREFIX.length()) {
                namespaces.byPrefix.put(name.substring(PREFIX.length()), value);
            }
        });
        return namespaces;
    }

    boolean isEmpty() {
        return byPrefix.isEmpty();
    }

    @Override
    public String getNamespaceURI(@Nullable String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("null prefix");
        }
        return switch (prefix) {
            case XMLConstants.XML_NS_PREFIX -> XMLConstants.XML_NS_URI;
            case XMLConstants.XMLNS_ATTRIBUTE -> XMLConstants.XMLNS_ATTRIBUTE_NS_URI;
            default -> byPrefix.getOrDefault(prefix, XMLConstants.NULL_NS_URI);
        };
    }

    @Override
    public @Nullable String getPrefix(@Nullable String namespaceURI) {
        return byPrefix.entrySet()
                .stream()
                .filter(e -> e.getValue().equals(namespaceURI))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    @Override
    public Iterator<String> getPrefixes(@Nullable String namespaceURI) {
        return byPrefix.entrySet()
                .stream()
                .filter(e -> e.getValue().equals(namespaceURI))
                .map(Map.Entry::getKey)
                .toList()
                .iterator();
    }

    @Override
    public String toString() {
        return byPrefix.isEmpty() ? "no namespaces" : List.of(byPrefix).toString();
    }
}
