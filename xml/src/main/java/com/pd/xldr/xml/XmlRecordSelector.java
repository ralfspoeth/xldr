package com.pd.xldr.xml;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One kind of record, addressed by an XPath expression that must select a node
 * set - {@code /root/fund} or {@code //position}. Each node of that set becomes
 * one row, and the field selectors are evaluated against it.
 */
public class XmlRecordSelector {

    private final String name;
    private final String selector;
    private final XPathExpression expression;
    private final Map<String, XmlFieldSelector> fieldSelectors = new LinkedHashMap<>();

    XmlRecordSelector(String name, String selector, XPathExpression expression) {
        this.name = Objects.requireNonNull(name);
        this.selector = selector;
        this.expression = Objects.requireNonNull(expression);
    }

    public String name() {
        return name;
    }

    public Map<String, XmlFieldSelector> fieldSelectors() {
        return Collections.unmodifiableMap(fieldSelectors);
    }

    void add(XmlFieldSelector fieldSelector) {
        var previous = fieldSelectors.putIfAbsent(fieldSelector.name(), fieldSelector);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "duplicate field selector " + fieldSelector.name() + " in record selector " + name);
        }
    }

    /**
     * The record nodes of {@code document}.
     */
    NodeList records(Document document) {
        try {
            return (NodeList) expression.evaluate(document, XPathConstants.NODESET);
        } catch (XPathExpressionException e) {
            throw new IllegalStateException(
                    "record selector " + name + " (" + selector + ") does not select a node set", e);
        }
    }

    @Override
    public String toString() {
        return name + "=" + selector;
    }
}
