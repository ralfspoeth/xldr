package com.pd.xldr.spec.io;

import com.pd.xldr.spec.*;
import io.github.ralfspoeth.xmls.Xml;
import io.github.ralfspoeth.xmls.XmlFunctions;
import org.w3c.dom.Element;

import java.io.Reader;
import java.util.Locale;


public class XmlMappingSpecReader implements MappingSpecReader {

    @Override
    public MappingSpec readFrom(Reader source) {
        try {
            var doc = Xml.parse(source);
            var root = doc.getDocumentElement();
            return new MappingSpec(
                    inputSpec(XmlFunctions.elements("input").apply(root).findFirst().orElseThrow()),
                    XmlFunctions.elements("mapping")
                            .apply(root)
                            .map(XmlMappingSpecReader::recordMappingSpec)
                            .toList(),
                    XmlFunctions.elements("load")
                            .apply(root)
                            .findFirst()
                            .map(XmlMappingSpecReader::loadSpec)
                            .orElseGet(LoadSpec::new)
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static InputSpec inputSpec(Element input) {
        return new InputSpec(
                input.getAttribute("mimeType"),
                null// recordSelectors
        );
    }

    private static LoadSpec loadSpec(Element load) {
        var policy = load.getAttribute("commitPolicy");
        return new LoadSpec(
                policy.isBlank() ? null : CommitPolicy.valueOf(policy.toUpperCase(Locale.ROOT))
        );
    }

    private static RecordMappingSpec recordMappingSpec(Element mapping) {
        return new RecordMappingSpec(
                mapping.getAttribute("recordSelector"),
                mapping.getAttribute("databaseTable"),
                XmlFunctions.elements("fieldMapping")
                        .apply(mapping)
                        .map(fm -> new FieldMappingSpec(
                                fm.getAttribute("fieldName"),
                                fm.getAttribute("databaseColumnName")
                        ))
                        .toList()
        );
    }
}
