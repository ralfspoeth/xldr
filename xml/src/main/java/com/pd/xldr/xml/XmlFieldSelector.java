package com.pd.xldr.xml;

import com.pd.xldr.spec.DataType;
import org.w3c.dom.Node;

import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Function;

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
public class XmlFieldSelector {

    private final String name;
    private final XPathExpression expression;
    private final String selector;
    private final DataType dataType;
    private final DateTimeFormatter dateFormat;

    XmlFieldSelector(String name, String selector, XPathExpression expression, DataType dataType,
                     DateTimeFormatter dateFormat) {
        this.name = Objects.requireNonNull(name);
        this.selector = selector;
        this.expression = Objects.requireNonNull(expression);
        this.dataType = dataType == null ? DataType.STRING : dataType;
        this.dateFormat = dateFormat;
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
     * Everything but {@code FLOAT} is taken as the string value and converted
     * from there: XPath 1.0 knows only doubles, so going through
     * {@code XPathConstants.NUMBER} would round a long integer and turn a
     * decimal into a binary approximation. An empty result becomes {@code null}
     * for the typed variants - the loader then binds SQL NULL - while a string
     * field keeps the empty string, since XPath cannot tell "no such element"
     * from "an element that is empty".
     */
    Object evaluate(Node record) {
        try {
            return switch (dataType) {
                case STRING -> string(record);
                case INTEGER -> nullOr(string(record), Long::valueOf);
                case DECIMAL -> nullOr(string(record), BigDecimal::new);
                case FLOAT -> {
                    var number = (Double) expression.evaluate(record, XPathConstants.NUMBER);
                    yield number == null || number.isNaN() ? null : number;
                }
                case DATE -> nullOr(string(record), this::toDateTime);
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

    private static <T> T nullOr(String raw, Function<String, T> convert) {
        return raw == null || raw.isBlank() ? null : convert.apply(raw.strip());
    }

    private LocalDateTime toDateTime(String raw) {
        if (dateFormat != null) {
            return LocalDateTime.parse(raw, dateFormat);
        }
        // accept a full timestamp as well as a plain date, the two common cases
        try {
            return LocalDateTime.parse(raw);
        } catch (RuntimeException e) {
            return LocalDate.parse(raw).atStartOfDay();
        }
    }

    @Override
    public String toString() {
        return name + "=" + selector + " (" + dataType + ")";
    }
}
