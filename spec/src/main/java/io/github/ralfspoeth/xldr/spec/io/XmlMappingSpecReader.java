package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.*;
import io.github.ralfspoeth.xmls.Xml;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static io.github.ralfspoeth.xmls.XmlFunctions.attributeValue;
import static io.github.ralfspoeth.xmls.XmlFunctions.elements;

/**
 * Reads a mapping specification from XML. Everything is carried in attributes,
 * the nesting mirrors the structure of the spec:
 *
 * <pre>
 * &lt;mappingSpec&gt;
 *     &lt;input mimeType="text/xml"&gt;
 *         &lt;recordSelector name="fund" selector="/root/fund"&gt;
 *             &lt;fieldSelector name="id" selector="@id" type="TEXT"/&gt;
 *         &lt;/recordSelector&gt;
 *     &lt;/input&gt;
 *     &lt;mapping recordSelector="fund" table="snmandat"&gt;
 *         &lt;fieldMapping fieldSelector="id" column="ident1_txt"/&gt;
 *     &lt;/mapping&gt;
 * &lt;/mappingSpec&gt;
 * </pre>
 * <p>
 * The element and attribute names are those of the JSON format, so a spec can
 * be transliterated between the two without renaming anything. {@code type} is
 * optional.
 * <p>
 * Elements and attributes the reader does not recognize are ignored at every
 * level, so an author may annotate a spec - for instance with a
 * {@code <comments>} element - without breaking it. No name is reserved.
 * {@code load} was, held since 0.2 against the return of the commit policy it
 * once carried; what a deployment needs to say about where a load goes is now
 * {@code target.properties} beside the spec, and a name kept open for something
 * that is not coming back is only a trap for whoever picks it.
 */
public class XmlMappingSpecReader implements MappingSpecReader {

    @Override
    public boolean accepts(Path path) {
        return path.getFileSystem()
                .getPathMatcher("glob:*.xml")
                .matches(path.getFileName());
    }

    @Override
    public MappingSpec read(InputStream source) {
        var root = Xml.parse(source).getDocumentElement();
        return new MappingSpec(
                elements("input")
                        .apply(root)
                        .findFirst()
                        .map(XmlMappingSpecReader::inputSpec)
                        .orElseThrow(() -> new IllegalArgumentException("no <input> element")),
                elements("mapping")
                        .apply(root)
                        .map(XmlMappingSpecReader::recordMappingSpec)
                        .toList()
        );
    }

    private static InputSpec inputSpec(Element input) {
        return new InputSpec(
                required(input, "mimeType"),
                elements("recordSelector")
                        .apply(input)
                        .map(XmlMappingSpecReader::recordSelectorSpec)
                        .toList(),
                elements("var")
                        .apply(input)
                        .map(XmlMappingSpecReader::varSpec)
                        .toList(),
                properties(input)
        );
    }

    /**
     * The settings of the adapter the input selects, carried as the attributes of
     * a {@code <properties>} child - {@code fieldSeparator}, {@code dateFormat},
     * {@code ns.f}, whatever that adapter understands:
     *
     * <pre>
     * &lt;properties fieldSeparator="," header="false"/&gt;
     * </pre>
     */
    private static Map<String, String> properties(Element input) {
        Map<String, String> properties = new LinkedHashMap<>();
        elements("properties").apply(input).forEach(element -> {
            var attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                var attribute = attributes.item(i);
                properties.put(attribute.getNodeName(), attribute.getNodeValue());
            }
        });
        return properties;
    }

    /**
     * A var reads whatever a field mapping reads, minus a field - which
     * {@link VarSpec} refuses itself, at any depth, so there is nothing to check
     * here. It used to be checked here and only at the top level, so a field
     * inside a lookup key slipped through and failed at load instead.
     */
    private static VarSpec varSpec(Element element) {
        return new VarSpec(required(element, "name"), valueSource(element));
    }

    /**
     * A {@code selector} attribute points at records in a tree or a sheet; a
     * {@code <discriminator>} child picks lines out of a flat file; and neither
     * says that the whole input holds one kind of record, which a CSV with a
     * header or a fixed-length file usually does. The three are the cases of
     * {@link io.github.ralfspoeth.xldr.spec.Locator}, and which of them this is
     * gets decided once, in {@link SpecNode#locator}.
     *
     * <pre>
     * &lt;recordSelector name="orders"&gt;
     *     &lt;discriminator nth="1" equals="O"/&gt;
     * &lt;/recordSelector&gt;
     * </pre>
     */
    private static RecordSelectorSpec recordSelectorSpec(Element recordSelector) {
        var name = required(recordSelector, "name");
        return new RecordSelectorSpec(
                name,
                node(recordSelector).locator("record selector '" + name + "'",
                        discriminator(recordSelector)),
                elements("fieldSelector")
                        .apply(recordSelector)
                        .map(XmlMappingSpecReader::fieldSelectorSpec)
                        .toList()
        );
    }

    /** A {@code <discriminator>} child, where there is one. */
    private static @Nullable Discriminator discriminator(Element recordSelector) {
        return elements("discriminator")
                .apply(recordSelector)
                .findFirst()
                .map(d -> node(d).discriminator())
                .orElse(null);
    }

    private static FieldSelectorSpec fieldSelectorSpec(Element fieldSelector) {
        return new FieldSelectorSpec(
                required(fieldSelector, "name"),
                node(fieldSelector).selector("a field selector"),
                attributeValue("type")
                        .apply(fieldSelector)
                        .map(type -> type.toUpperCase(Locale.ROOT))
                        .map(DataType::valueOf)
                        .orElse(null)
        );
    }

    private static RecordMappingSpec recordMappingSpec(Element mapping) {
        return new RecordMappingSpec(
                required(mapping, "recordSelector"),
                required(mapping, "table"),
                elements("fieldMapping")
                        .apply(mapping)
                        .map(fm -> new FieldMappingSpec(required(fm, "column"), valueSource(fm)))
                        .toList(),
                node(mapping).whole("limit").orElse(null)
        );
    }

    /**
     * A field mapping carries exactly one source: a {@code fieldSelector},
     * {@code constant}, {@code var} or {@code expr} attribute, or a child
     * {@code <lookup>} element. Which one, and the refusal when it is not one, is
     * {@link SpecNode#source()}; where the lookup sits is this format's business.
     */
    private static ValueSource valueSource(Element fm) {
        var lookup = elements("lookup").apply(fm).findFirst().orElse(null);
        var fn = elements("fn").apply(fm).findFirst().orElse(null);
        if (lookup == null && fn == null) {
            return node(fm).source();
        }
        if (lookup != null && fn != null) {
            throw new IllegalArgumentException(
                    "<" + fm.getNodeName() + "> has both a <lookup> and an <fn>, and one source is wanted");
        }
        if (node(fm).hasSource()) {
            throw new IllegalArgumentException("a <" + (fn == null ? "lookup" : "fn")
                    + "> field mapping must carry no source attribute");
        }
        if (fn != null) {
            return functionCall(fn);
        }
        return new ValueSource.Lookup(
                required(lookup, "table"),
                required(lookup, "column"),
                required(lookup, "keyColumn"),
                node(lookup).source());
    }

    /**
     * A call, as an element with a child per argument:
     *
     * <pre>
     * &lt;fn name="next_id" type="INTEGRAL"&gt;
     *     &lt;arg constant="7"/&gt;
     *     &lt;arg var="feed"/&gt;
     * &lt;/fn&gt;
     * </pre>
     *
     * The first repeated child this format carries inside a value source. A
     * {@code <lookup>} could hold its one key as attributes on itself; a call has
     * as many arguments as it has, so each needs an element - and an {@code <arg>}
     * carries exactly what a {@code <fieldMapping>} carries, which is what lets
     * the same method read both and lets an argument be a nested {@code <lookup>}
     * or {@code <fn>}.
     * <p>
     * {@code type} is required, where a field selector's may be left out and
     * defaults to {@code TEXT}: the loader registers an OUT parameter before the
     * call and has nothing to infer it from.
     */
    private static ValueSource.FunctionCall functionCall(Element fn) {
        return new ValueSource.FunctionCall(
                required(fn, "name"),
                DataType.valueOf(required(fn, "type").toUpperCase(Locale.ROOT)),
                elements("arg").apply(fn)
                        .map(XmlMappingSpecReader::valueSource)
                        .toList());
    }

    /**
     * This format's answers to the five questions {@link SpecNode} asks, which is
     * all it takes to inherit the rules about what a spec may say.
     * <p>
     * An attribute is text and carries no type of its own, so {@code string} and
     * {@code scalar} are the same question here and a constant is always a string.
     * The distinction JSON draws between {@code "1"} and {@code 1} is drawn in this
     * format by which attribute was written, which is the reason the format has
     * both {@code selector} and {@code nth} rather than one attribute read two
     * ways.
     */
    private record Node(Element element) implements SpecNode {

        @Override
        public Optional<String> string(String name) {
            return attributeValue(name).apply(element);
        }

        @Override
        public Optional<String> scalar(String name) {
            return string(name);
        }

        @Override
        public Optional<Integer> whole(String name) {
            return string(name).map(value -> wholeNumber(name, value));
        }

        @Override
        public Optional<ValueSource.Constant> constant() {
            return string("constant").map(ValueSource.Constant::new);
        }

        /**
         * The element as written, attributes and all. A complaint about
         * {@code <fieldSelector name="id"/>} is worth little without the name, and
         * a spec has enough of them that the tag on its own does not locate one.
         */
        @Override
        public String shown() {
            var text = new StringBuilder("<").append(element.getNodeName());
            var attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                var attribute = attributes.item(i);
                text.append(' ').append(attribute.getNodeName())
                        .append("=\"").append(attribute.getNodeValue()).append('"');
            }
            return text.append("/>").toString();
        }
    }

    private static SpecNode node(Element element) {
        return new Node(element);
    }

    private static int wholeNumber(String name, String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    name + " is a whole number, was '" + value + "'", e);
        }
    }

    /**
     * {@code attributeValue} yields an empty Optional for an absent attribute,
     * where {@code getAttribute} cannot tell that from an empty one - which is
     * the difference between a broken spec and a deliberately empty value.
     */
    private static String required(Element element, String attribute) {
        return attributeValue(attribute)
                .apply(element)
                .orElseThrow(() -> new IllegalArgumentException(
                        "<" + element.getNodeName() + "> has no " + attribute + " attribute"));
    }
}
