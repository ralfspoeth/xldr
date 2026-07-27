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
 *   <li>{@link Lookup} - the value read from a reference table, emitted as an
 *       inline scalar subquery {@code (select column from table where keyColumn
 *       = key)} where the {@code key} is itself a {@code Field}, {@code Constant}
 *       or {@code Var}. A key that matches no row yields SQL NULL.</li>
 *   <li>{@link Var} - a reference to an input-level variable, evaluated once per
 *       load and then bound as a parameter. See {@link VarSpec}.</li>
 *   <li>{@link Expr} - a {@code ${...}} template evaluated in the JVM to a value
 *       that is bound as a parameter. It interpolates variables and a small set
 *       of built-in functions ({@code nextval}, {@code now}); it never emits
 *       SQL.</li>
 * </ul>
 * <p>
 * Every value reaches the database as a bound parameter or an identifier that is
 * normalized and, where needed, quoted - a spec never contributes raw SQL.
 */
public sealed interface ValueSource extends Serializable {

    /**
     * A field of the input record.
     *
     * @param fieldName the name of a field selector of the record selector being
     *                  mapped
     */
    record Field(String fieldName) implements ValueSource {}

    /**
     * A fixed value from the spec.
     *
     * @param value the value, whose Java type follows the literal it was read
     *              from: a {@code String}, a {@code BigDecimal} or a {@code Boolean}
     */
    record Constant(Object value) implements ValueSource {}

    /**
     * A value read from a reference table.
     *
     * @param table     the table to read from
     * @param column    the column whose value is taken
     * @param keyColumn the column the key is matched against
     * @param key       the value matched against {@code keyColumn}; a {@code Field},
     *                  {@code Constant} or {@code Var}, never a nested {@code Lookup}
     */
    record Lookup(String table, String column, String keyColumn, ValueSource key) implements ValueSource {}

    /**
     * A reference to an input {@link VarSpec} by name; resolves to that
     * variable's value, which is computed once at the start of the load.
     *
     * @param name the name of a variable the input declares
     */
    record Var(String name) implements ValueSource {}

    /**
     * A {@code ${...}} template. Literal text is interleaved with holes, each a
     * variable reference ({@code ${xldr.filename}}, a var, or - in a field
     * mapping - a field) or a call to a built-in function ({@code ${nextval('s')}},
     * {@code ${now()}}). A template that is a single hole yields that hole's
     * value with its native type; anything else yields the pieces concatenated
     * as a string.
     *
     * @param template the template text, holes and all
     */
    record Expr(String template) implements ValueSource {}
}
