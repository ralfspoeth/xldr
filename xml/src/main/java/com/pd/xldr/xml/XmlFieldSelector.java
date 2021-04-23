package com.pd.xldr.xml;

import com.pd.xldr.spec.Type;
import org.w3c.dom.Node;

import javax.xml.namespace.QName;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import java.util.Objects;

import static com.pd.xldr.xml.XmlObjects.XP;

public class XmlFieldSelector {

    private final String name;
    private final XPathExpression fieldExpression;
    private final QName nodeType;
    private final Type type;

    public XmlFieldSelector(String name, XPathExpression fieldExpression, Type type) {
        this.name = name;
        this.fieldExpression = Objects.requireNonNull(fieldExpression);
        this.nodeType = switch (type) {
            case DECIMAL, INTEGER -> XPathConstants.NUMBER;
            default -> XPathConstants.STRING;
        };
        this.type = type;
    }

    public XmlFieldSelector(String name, String fieldExpression, Type type) throws XPathExpressionException {
        this(name, XP.compile(fieldExpression), type);
    }

    public String name() {
        return name;
    }

    public QName nodeType() {
        return nodeType;
    }

    public XPathExpression fieldExpression() {
        return fieldExpression;
    }

    public Type type() {
        return type;
    }

    Object evaluate(Node recordNode) {
        try {
            return fieldExpression.evaluate(recordNode, nodeType);
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException(fieldExpression.toString());
        }
    }

//    public XmlFieldSelector(String name, XPathExpression fieldExpression) {
//        this(name, fieldExpression, XPathConstants.STRING);
//    }
//
//    public XmlFieldSelector(String name, String fieldExpression) throws XPathExpressionException {
//        this(name, fieldExpression, XPathConstants.STRING);
//    }

//    public Object field(Node record) {
//        try {
//            return fieldExpression.evaluate(record, nodeType);
//        } catch (XPathExpressionException e) {
//            throw new RuntimeException(e);
//        }
//    }
}
