package com.pd.xldr.spec.io;

import com.pd.xldr.spec.*;
import io.github.ralfspoeth.xmls.Xml;
import org.w3c.dom.Element;

import java.io.Reader;
import java.util.Locale;

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
 *             &lt;fieldSelector name="id" selector="@id" type="STRING"/&gt;
 *         &lt;/recordSelector&gt;
 *     &lt;/input&gt;
 *     &lt;mapping recordSelector="fund" databaseTable="snmandat"&gt;
 *         &lt;fieldMapping fieldSelector="id" databaseColumn="ident1_txt"/&gt;
 *     &lt;/mapping&gt;
 *     &lt;load commitPolicy="ON_CLOSE"/&gt;
 * &lt;/mappingSpec&gt;
 * </pre>
 *
 * The element and attribute names are those of the JSON format, so a spec can
 * be transliterated between the two without renaming anything. {@code type} and
 * the whole {@code load} element are optional.
 */
public class XmlMappingSpecReader implements MappingSpecReader {

    @Override
    public MappingSpec readFrom(Reader source) {
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
                        .toList(),
                elements("load")
                        .apply(root)
                        .findFirst()
                        .map(XmlMappingSpecReader::loadSpec)
                        .orElseGet(LoadSpec::new)
        );
    }

    private static InputSpec inputSpec(Element input) {
        return new InputSpec(
                required(input, "mimeType"),
                attributeValue("sentinel").apply(input).orElse(null),
                elements("recordSelector")
                        .apply(input)
                        .map(XmlMappingSpecReader::recordSelectorSpec)
                        .toList()
        );
    }

    private static RecordSelectorSpec recordSelectorSpec(Element recordSelector) {
        return new RecordSelectorSpec(
                required(recordSelector, "name"),
                required(recordSelector, "selector"),
                elements("fieldSelector")
                        .apply(recordSelector)
                        .map(XmlMappingSpecReader::fieldSelectorSpec)
                        .toList()
        );
    }

    private static FieldSelectorSpec fieldSelectorSpec(Element fieldSelector) {
        return new FieldSelectorSpec(
                required(fieldSelector, "name"),
                required(fieldSelector, "selector"),
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
                required(mapping, "databaseTable"),
                elements("fieldMapping")
                        .apply(mapping)
                        .map(fm -> new FieldMappingSpec(columnSource(fm), required(fm, "databaseColumn")))
                        .toList(),
                attributeValue("limit").apply(mapping).map(Integer::valueOf).orElse(null)
        );
    }

    /**
     * A field mapping carries exactly one source: a {@code fieldSelector},
     * {@code constant} or {@code function} attribute, or a child {@code <lookup>}
     * element. A constant in XML is always a string - attribute values carry no
     * type.
     */
    private static ColumnSource columnSource(Element fm) {
        var lookup = elements("lookup").apply(fm).findFirst();
        if (lookup.isPresent()) {
            if (hasBasicSource(fm)) {
                throw new IllegalArgumentException("a <lookup> field mapping must carry no source attribute");
            }
            var lk = lookup.get();
            return new ColumnSource.Lookup(
                    required(lk, "table"),
                    required(lk, "column"),
                    required(lk, "keyColumn"),
                    basicSource(lk));
        }
        return basicSource(fm);
    }

    private static boolean hasBasicSource(Element e) {
        return attributeValue("fieldSelector").apply(e).isPresent()
                || attributeValue("function").apply(e).isPresent()
                || attributeValue("constant").apply(e).isPresent();
    }

    private static ColumnSource basicSource(Element e) {
        var field = attributeValue("fieldSelector").apply(e);
        var function = attributeValue("function").apply(e);
        var constant = attributeValue("constant").apply(e);

        var present = (field.isPresent() ? 1 : 0) + (function.isPresent() ? 1 : 0) + (constant.isPresent() ? 1 : 0);
        if (present != 1) {
            throw new IllegalArgumentException("needs exactly one of fieldSelector, constant, function");
        }
        if (field.isPresent()) {
            return new ColumnSource.Field(field.get());
        } else if (function.isPresent()) {
            return new ColumnSource.Function(function.get());
        } else {
            return new ColumnSource.Constant(constant.get());
        }
    }

    private static LoadSpec loadSpec(Element load) {
        return new LoadSpec(
                attributeValue("commitPolicy")
                        .apply(load)
                        .map(policy -> policy.toUpperCase(Locale.ROOT))
                        .map(CommitPolicy::valueOf)
                        .orElse(null)
        );
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
