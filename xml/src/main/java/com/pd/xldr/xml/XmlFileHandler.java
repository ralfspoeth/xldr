package com.pd.xldr.xml;

import com.pd.xldr.ia.Field;
import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.Result;
import com.pd.xldr.ia.Row;
import com.pd.xldr.spec.InputSpec;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Reads records out of an XML document, both record and field selectors being
 * XPath expressions.
 * <p>
 * Every expression of the input spec is compiled once, in the constructor, so a
 * malformed selector is reported when the adapter is created rather than half
 * way through a load - and the compiled form is reused for every record.
 * <p>
 * One adapter serves all record selectors of one file, but it is not safe for
 * use by several threads at once: {@code XPathExpression} and
 * {@code DocumentBuilderFactory} are not thread safe. That matches how the
 * application uses it, one adapter per file being loaded.
 */
class XmlFileHandler implements InputAdapter {

    /**
     * Optional adapter property naming the pattern for {@code DATE} fields;
     * without it an ISO timestamp and a plain ISO date are both accepted.
     */
    static final String DATE_FORMAT = "dateFormat";

    private final Map<String, XmlRecordSelector> recordSelectors = new HashMap<>();
    private final DocumentBuilderFactory parsers;

    XmlFileHandler(InputSpec spec, Properties properties) {
        var namespaces = Namespaces.of(properties);
        var dateFormat = properties.containsKey(DATE_FORMAT)
                ? DateTimeFormatter.ofPattern(properties.getProperty(DATE_FORMAT))
                : null;

        var xpath = newXPath(namespaces);
        for (var recordSpec : spec.recordSelectors()) {
            var record = new XmlRecordSelector(
                    recordSpec.name(), recordSpec.selector(), compile(xpath, recordSpec.selector()));
            if (recordSelectors.putIfAbsent(recordSpec.name(), record) != null) {
                throw new IllegalArgumentException("duplicate record selector " + recordSpec.name());
            }
            for (var fieldSpec : recordSpec.fieldSelectors()) {
                record.add(new XmlFieldSelector(
                        fieldSpec.name(),
                        fieldSpec.selector(),
                        compile(xpath, fieldSpec.selector()),
                        fieldSpec.dataType(),
                        dateFormat));
            }
        }
        this.parsers = newParserFactory();
    }

    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        var record = recordSelectors.get(recordSelector);
        if (record == null) {
            throw new IllegalArgumentException("no record selector named " + recordSelector
                    + "; the input spec declares " + recordSelectors.keySet());
        }
        var selected = selected(record, fieldSelectors);

        try {
            var document = parsers.newDocumentBuilder().parse(source);
            var nodes = record.records(document);
            var rows = IntStream.range(0, nodes.getLength())
                    .mapToObj(nodes::item)
                    .map(node -> (Row) new XmlRow(node, record));
            return new Result(
                    selected.stream().map(fs -> new Field(fs.name(), fs.dataType().clazz())).toList(),
                    rows);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("cannot parse the XML input", e);
        }
    }

    /**
     * The field selectors asked for, in the order of the input spec.
     */
    private static List<XmlFieldSelector> selected(XmlRecordSelector record, Set<String> wanted) {
        var unknown = wanted.stream().filter(name -> !record.fieldSelectors().containsKey(name)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("record selector " + record.name()
                    + " declares no field selector(s) " + unknown);
        }
        return record.fieldSelectors()
                .values()
                .stream()
                .filter(fs -> wanted.contains(fs.name()))
                .toList();
    }

    private record XmlRow(Node node, XmlRecordSelector record) implements Row {
        @Override
        public Object get(String name) {
            var selector = record.fieldSelectors().get(name);
            return selector == null ? null : selector.evaluate(node);
        }
    }

    private static XPath newXPath(Namespaces namespaces) {
        var xpath = XPathFactory.newDefaultInstance().newXPath();
        if (!namespaces.isEmpty()) {
            xpath.setNamespaceContext(namespaces);
        }
        return xpath;
    }

    private static javax.xml.xpath.XPathExpression compile(XPath xpath, String selector) {
        try {
            return xpath.compile(selector);
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException("not a valid XPath expression: " + selector, e);
        }
    }

    private static DocumentBuilderFactory newParserFactory() {
        var factory = DocumentBuilderFactory.newDefaultInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("cannot configure a safe XML parser", e);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);
        return factory;
    }
}
