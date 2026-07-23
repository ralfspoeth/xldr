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
        // the mapping spec is our own format and uses no namespaces, so the
        // plain parser is the right one here
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
                // absent means STRING, which FieldSelectorSpec applies for null
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
                        .map(fm -> new FieldMappingSpec(
                                required(fm, "fieldSelector"),
                                required(fm, "databaseColumn")))
                        .toList()
        );
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
