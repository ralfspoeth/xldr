package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;

/**
 * Where the value of a mapped database column comes from. Exactly one of:
 * <ul>
 *   <li>{@link Field} - a field of the input record, resolved by the adapter and
 *       bound as a statement parameter (the ordinary case).</li>
 *   <li>{@link Constant} - a fixed value from the spec, bound as a parameter. Its
 *       Java type follows the JSON literal: a {@code String}, a
 *       {@code BigDecimal} for a number, or a {@code Boolean}.</li>
 *   <li>{@link Function} - a raw SQL expression such as {@code sysdate} or
 *       {@code myseq.nextval}, emitted inline in the {@code values(...)} list
 *       rather than bound. The text is spec-authored and trusted.</li>
 *   <li>{@link Lookup} - the value read from a reference table, emitted as an
 *       inline scalar subquery {@code (select column from table where keyColumn
 *       = key)} where the {@code key} is itself a {@code Field}, {@code Constant}
 *       or {@code Function}. A key that matches no row yields SQL NULL.</li>
 * </ul>
 */
public sealed interface ColumnSource extends Serializable {

    record Field(String fieldName) implements ColumnSource {}

    record Constant(Object value) implements ColumnSource {}

    record Function(String sql) implements ColumnSource {}

    /**
     * @param key the value matched against {@code keyColumn}; a {@code Field},
     *            {@code Constant} or {@code Function}, never a nested {@code Lookup}
     */
    record Lookup(String table, String column, String keyColumn, ColumnSource key) implements ColumnSource {}
}
