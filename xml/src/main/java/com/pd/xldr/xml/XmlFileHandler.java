package com.pd.xldr.xml;


import com.pd.xldr.ia.Field;
import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.Result;
import com.pd.xldr.ia.Row;
import com.pd.xldr.spec.InputSpec;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class XmlFileHandler implements InputAdapter {

    private final Map<String, XmlRecordSelector> recordSelectorMap = new HashMap<>();

    public XmlFileHandler(InputSpec spec) {
        for (var rsSpec : spec.recordSelectors()) {
            try {
                var rs = new XmlRecordSelector(rsSpec.name(), rsSpec.selector());
                var prev = recordSelectorMap
                        .putIfAbsent(rsSpec.name(), rs);
                if (prev != null) throw new IllegalArgumentException("duplicate record selector " + rsSpec.name());
                for (var fsSpec : rsSpec.fieldSelectors()) {
                    var fs = new XmlFieldSelector(fsSpec.name(), fsSpec.selector(), fsSpec.type());
                    rs.fieldSelectors().putIfAbsent(fsSpec.name(), fs);
                }
            } catch (XPathExpressionException xpex) {
                throw new IllegalArgumentException(xpex);
            }
        }
    }

    @Override
    public Result parse(InputStream source, String recordSelector, List<String> fieldSelectors) throws IOException {
        try {
            var doc = XmlObjects.PARSER.newDocumentBuilder().parse(source);
            var rs = recordSelectorMap.get(recordSelector);

            List<Field> fieldList = fieldSelectors.stream()
                    .map(selName -> rs.fieldSelectors().get(selName))
                    .map(Optional::ofNullable)
                    .map(os -> os.map(sel -> new Field(sel.name(), sel.type().clazz())).orElse(new Field("<<UNNAMED>>", Object.class)))
                    .collect(Collectors.toList());

            var recs = (NodeList) rs.recordExpression().evaluate(doc, XPathConstants.NODESET);

            var rowStream = StreamSupport.stream(new Spliterator<Row>() {
                private int index = 0;

                @Override
                public boolean tryAdvance(Consumer<? super Row> action) {
                    var row = new Row() {
                        Node recordNode = recs.item(index++);

                        @Override
                        public Object get(String name) {
                            return Optional.ofNullable(rs.fieldSelectors().get(name)).map(s -> s.evaluate(recordNode)).orElse("");
                        }
                    };
                    action.accept(row);
                    return index < recs.getLength();
                }

                @Override
                public Spliterator<Row> trySplit() {
                    return null;
                }

                @Override
                public long estimateSize() {
                    return recs.getLength();
                }

                @Override
                public int characteristics() {
                    return Spliterator.IMMUTABLE | Spliterator.ORDERED | Spliterator.NONNULL;
                }
            }, false);
            return new Result(fieldList, rowStream);
        } catch (SAXException | ParserConfigurationException | XPathExpressionException e) {
            throw new RuntimeException(e);
        }
    }
}
