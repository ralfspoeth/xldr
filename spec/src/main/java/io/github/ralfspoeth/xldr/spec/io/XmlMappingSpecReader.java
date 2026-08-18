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
 * {@code <comments>} element - without breaking it. The name {@code load} is
 * reserved (it carried the commit policy once and may return) and must not be
 * repurposed.
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

    private static VarSpec varSpec(Element element) {
        var source = valueSource(element);
        if (source instanceof ValueSource.Field) {
            throw new IllegalArgumentException("a var must be row-independent, not a fieldSelector");
        }
        return new VarSpec(required(element, "name"), source);
    }

    /**
     * Both ways of selecting records are optional, and no record selector has
     * both. A {@code selector} attribute points at records in a tree or a sheet;
     * a {@code <discriminator>} child picks lines out of a flat file; and neither
     * says that the whole input holds one kind of record, which a CSV with a
     * header or a fixed-length file usually does.
     *
     * <pre>
     * &lt;recordSelector name="orders"&gt;
     *     &lt;discriminator column="1" equals="O"/&gt;
     * &lt;/recordSelector&gt;
     * </pre>
     */
    private static RecordSelectorSpec recordSelectorSpec(Element recordSelector) {
        return new RecordSelectorSpec(
                required(recordSelector, "name"),
                attributeValue("selector").apply(recordSelector).orElse(null),
                discriminator(recordSelector),
                elements("fieldSelector")
                        .apply(recordSelector)
                        .map(XmlMappingSpecReader::fieldSelectorSpec)
                        .toList()
        );
    }

    /**
     * A discriminator says where to look and what for: exactly one of
     * {@code column} and {@code selector}, and exactly one of {@code equals} and
     * {@code matches}.
     */
    private static @Nullable Discriminator discriminator(Element recordSelector) {
        var element = elements("discriminator").apply(recordSelector).findFirst().orElse(null);
        if (element == null) {
            return null;
        }
        var where = selector(element, "a discriminator");
        var literal = attributeValue("equals").apply(element);
        var regex = attributeValue("matches").apply(element);
        if (literal.isPresent() && regex.isPresent()) {
            throw new IllegalArgumentException("a discriminator tests equals or matches, not both: '"
                    + literal.get() + "' and /" + regex.get() + "/");
        }
        if (literal.isPresent()) {
            return new Discriminator.Equals(where, literal.get());
        }
        if (regex.isPresent()) {
            return Discriminator.matching(where, regex.get());
        }
        throw new IllegalArgumentException("a discriminator needs equals or matches; "
                + where + " on its own says where to look and not what for");
    }

    private static FieldSelectorSpec fieldSelectorSpec(Element fieldSelector) {
        return new FieldSelectorSpec(
                required(fieldSelector, "name"),
                selector(fieldSelector, "a field selector"),
                attributeValue("type")
                        .apply(fieldSelector)
                        .map(type -> type.toUpperCase(Locale.ROOT))
                        .map(DataType::valueOf)
                        .orElse(null)
        );
    }

    /**
     * Exactly one of {@code selector} - the adapter's own syntax - and
     * {@code column}, a position counted from one.
     * <p>
     * This is the reason the format uses two names rather than one attribute of
     * two types: an XML attribute is text, so {@code selector="3"} could only ever
     * have been told from a column by guessing at its shape, and a header naming a
     * column {@code 3} would have made the guess wrong. Here the two are different
     * attributes and the schema types the second, so {@code column="first"} does
     * not reach this code at all.
     */
    private static Selector selector(Element element, String what) {
        var text = attributeValue("selector").apply(element);
        var column = attributeValue("column").apply(element);
        if (text.isPresent() && column.isPresent()) {
            throw new IllegalArgumentException(what + " has both selector='" + text.get()
                    + "' and column='" + column.get() + "', which are two answers to one question");
        }
        if (text.isPresent()) {
            return new Selector.Text(text.get());
        }
        if (column.isPresent()) {
            return new Selector.Column(columnIndex(column.get(), what));
        }
        throw new IllegalArgumentException(what + " needs a selector or a column");
    }

    private static int columnIndex(String value, String what) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    what + ": a column is a whole number counted from one, was '" + value + "'", e);
        }
    }

    private static RecordMappingSpec recordMappingSpec(Element mapping) {
        return new RecordMappingSpec(
                required(mapping, "recordSelector"),
                required(mapping, "table"),
                elements("fieldMapping")
                        .apply(mapping)
                        .map(fm -> new FieldMappingSpec(required(fm, "column"), valueSource(fm)))
                        .toList(),
                attributeValue("limit").apply(mapping).map(Integer::valueOf).orElse(null)
        );
    }

    /**
     * A field mapping carries exactly one source: a {@code fieldSelector},
     * {@code constant}, {@code var} or {@code expr} attribute, or a child
     * {@code <lookup>} element. A constant in XML is always a string - attribute
     * values carry no type.
     */
    private static ValueSource valueSource(Element fm) {
        var lookup = elements("lookup").apply(fm).findFirst();
        if (lookup.isPresent()) {
            if (hasBasicSource(fm)) {
                throw new IllegalArgumentException("a <lookup> field mapping must carry no source attribute");
            }
            var lk = lookup.get();
            return new ValueSource.Lookup(
                    required(lk, "table"),
                    required(lk, "column"),
                    required(lk, "keyColumn"),
                    basicSource(lk));
        }
        return basicSource(fm);
    }

    private static boolean hasBasicSource(Element e) {
        return attributeValue("fieldSelector").apply(e).isPresent()
                || attributeValue("constant").apply(e).isPresent()
                || attributeValue("var").apply(e).isPresent()
                || attributeValue("expr").apply(e).isPresent();
    }

    private static ValueSource basicSource(Element e) {
        var field = attributeValue("fieldSelector").apply(e);
        var constant = attributeValue("constant").apply(e);
        var varRef = attributeValue("var").apply(e);
        var expr = attributeValue("expr").apply(e);

        var present = (field.isPresent() ? 1 : 0) + (constant.isPresent() ? 1 : 0)
                + (varRef.isPresent() ? 1 : 0) + (expr.isPresent() ? 1 : 0);
        if (present != 1) {
            throw new IllegalArgumentException("needs exactly one of fieldSelector, constant, var, expr");
        }
        if (field.isPresent()) {
            return new ValueSource.Field(field.get());
        } else if (varRef.isPresent()) {
            return new ValueSource.Var(varRef.get());
        } else if (expr.isPresent()) {
            return new ValueSource.Expr(expr.get());
        } else {
            return new ValueSource.Constant(constant.get());
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
