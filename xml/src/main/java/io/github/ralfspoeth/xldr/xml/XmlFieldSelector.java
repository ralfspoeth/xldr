package io.github.ralfspoeth.xldr.xml;

import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.spec.DataType;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Node;

import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import java.util.Objects;

/**
 * One field of a record, addressed by an XPath expression evaluated relative to
 * the record node.
 * <p>
 * The expression need not be relative: {@code @id} and {@code ./name} address
 * something inside the record, while {@code /root/@version} or {@code //x[1]}
 * reach out into the whole document and yield the same value for every record.
 * A literal such as {@code 'PD'} is a perfectly good selector too, which is how
 * a constant column is expressed.
 */
class XmlFieldSelector {

    private final String name;
    private final XPathExpression expression;
    private final String selector;
    private final DataType dataType;
    private final Formats formats;

    XmlFieldSelector(
            String name,
            String selector,
            XPathExpression expression,
            @Nullable DataType dataType,
            Formats formats
    ) {
        this.name = Objects.requireNonNull(name);
        this.selector = selector;
        this.expression = Objects.requireNonNull(expression);
        this.dataType = dataType == null ? DataType.TEXT : dataType;
        this.formats = formats;
    }

    public String name() {
        return name;
    }

    public DataType dataType() {
        return dataType;
    }

    /**
     * Evaluates the expression against {@code record} and converts the result to
     * the declared {@link DataType}.
     * <p>
     * Everything but {@code FP} is taken as the string value and converted
     * from there by the shared {@link Formats}: XPath 1.0 knows only doubles, so
     * going through {@code XPathConstants.NUMBER} would round a long integer and
     * turn a decimal into a binary approximation. An empty result becomes
     * {@code null} for the typed variants - the loader then binds SQL NULL -
     * while a string field keeps the empty string, since XPath cannot tell
     * "no such element" from "an element that is empty".
     */
    @Nullable Object evaluate(Node record) {
        try {
            return switch (dataType) {
                case TEXT -> string(record);
                case FP -> {
                    var number = (Double) expression.evaluate(record, XPathConstants.NUMBER);
                    yield number == null || number.isNaN() ? null : number;
                }
                // the shared formats apply the configured numberFormat/dateFormat
                // and yield null for an empty result
                case INTEGRAL, DECIMAL, DATE -> formats.parse(dataType, string(record));
            };
        } catch (XPathExpressionException e) {
            throw new IllegalStateException("cannot evaluate " + selector + " for field " + name, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "cannot read field " + name + " as " + dataType + " from " + selector, e);
        }
    }

    private String string(Node record) throws XPathExpressionException {
        return (String) expression.evaluate(record, XPathConstants.STRING);
    }

    @Override
    public String toString() {
        return name + "=" + selector + " (" + dataType + ")";
    }
}
