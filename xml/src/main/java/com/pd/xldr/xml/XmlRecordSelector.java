package com.pd.xldr.xml;

import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.pd.xldr.xml.XmlObjects.XP;


public class XmlRecordSelector {

    private final String name;
    private final XPathExpression recordExpression;
    private final Map<String, XmlFieldSelector> fieldSelectorMap = new HashMap<>();

    public XmlRecordSelector(String name, XPathExpression recordExpression) {
        this.name = Objects.requireNonNull(name);
        this.recordExpression = Objects.requireNonNull(recordExpression);
    }

    public XmlRecordSelector(String name, String recordExpression) throws XPathExpressionException {
        this(name, XP.compile(recordExpression));
    }

    public String name() {
        return name;
    }

    public XPathExpression recordExpression() {
        return recordExpression;
    }

    Map<String, XmlFieldSelector> fieldSelectors() {
        return fieldSelectorMap;
    }

    public Map<String, XmlFieldSelector> fieldSelectorMap() {
        return Collections.unmodifiableMap(fieldSelectorMap);
    }


    /*
    public Stream<Node> records(FileHandler fileHandler) throws IOException {
        if (fileHandler instanceof XmlFileHandler) {
            XmlFileHandler xfh = (XmlFileHandler) fileHandler;
            try {
                final var nl = (NodeList) recordExpression.evaluate(xfh.open(), XPathConstants.NODESET);
                return StreamSupport.stream(new Spliterators.AbstractSpliterator<Node>(nl.getLength(), Spliterator.SIZED) {
                    private int ptr = 0;

                    @Override
                    public boolean tryAdvance(Consumer<? super Node> action) {
                        if (ptr < nl.getLength()) {
                            action.accept(nl.item(ptr++));
                            return true;
                        } else {
                            return false;
                        }
                    }
                }, false);
            } catch (ParserConfigurationException | SAXException | XPathExpressionException e) {
                throw new RuntimeException(e);
            }
        } else return Stream.empty();
    }
    */

}
