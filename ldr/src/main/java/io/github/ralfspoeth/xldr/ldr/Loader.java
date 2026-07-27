package io.github.ralfspoeth.xldr.ldr;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.ValueSource;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.RecordMappingSpec;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Inserts the records of a single input into the target database.
 * <p>
 * The connection is supplied by the caller - which database is fed is a
 * deployment concern of the application, not part of the mapping. The loader
 * borrows the connection: it switches auto-commit off for the duration of the
 * load, commits the whole input on {@link #close()} - or rolls it all back if
 * any record mapping failed - then closes the connection, which returns it to
 * its pool (the pool owns its state). Intent is insert only.
 * <p>
 * Any input variables are evaluated once, up front, so a value looked up from a
 * reference table is read a single time and shared across the whole load.
 * Expression sources ({@code ${...}} templates) draw on application-provided
 * ambient variables and in-memory, per-load sequences; they never touch the
 * database.
 * <p>
 * The inserts are batched, which changes nothing about what a load means - the
 * transaction is still the whole input - but spares a round trip per row. The
 * records themselves are consumed as the adapter produces them, so neither the
 * input nor the batch grows with the size of the file.
 */
public class Loader implements AutoCloseable {
    private static final Pattern QS_PATTERN = Pattern.compile("\".*\"");

    /**
     * How many inserts are sent in one round trip. The point of batching is the
     * round trips, not the batch: against a remote database one statement per
     * row is dominated by latency. A few thousand is where the gain flattens out
     * while the driver's buffer stays modest.
     */
    private static final int BATCH_SIZE = 1_000;

    private final MappingSpec mappingSpec;
    private final Connection connection;
    /** application-provided values available to expressions, keyed {@code xldr.*} */
    private final Map<String, Object> ambient;
    /** in-memory sequences, one per name, living for the duration of this load */
    private final Map<String, Integer> sequences = new HashMap<>();
    private final Map<String, Object> varValues;
    private final Map<TabCol, PreparedStatement> statementCache = new HashMap<>();
    private boolean failed = false;

    /**
     * Key of the prepared-statement cache: a target table plus the columns of one
     * insert. The same table may be the target of several mappings, each
     * producing its own rows and possibly covering a different set of columns.
     * <p>
     * The columns are held in an ordered, immutable {@code List} on purpose - the
     * position of a column is its bind-parameter position. A {@code Set} would
     * let {@code (a, b)} and {@code (b, a)} collide on one cache entry and bind
     * values into the wrong columns.
     */
    record TabCol(String table, List<String> columns, List<String> valueExprs) {
        TabCol {
            Objects.requireNonNull(table);
            table = normalizeIdentifier(table);
            columns = columns.stream().map(Loader::normalizeIdentifier).toList();
            valueExprs = List.copyOf(valueExprs);
        }

        String insertStatement() {
            var columnList = String.join(", ", columns);
            var values = String.join(", ", valueExprs);
            return String.format("insert into %s(%s) values(%s)", table, columnList, values);
        }
    }

    /**
     * Unquoted SQL identifiers are case-insensitive in every target database;
     * they only disagree on the case they fold to - Oracle and H2 fold up,
     * PostgreSQL folds down. Folding to upper case here is portable because we
     * never add quotes: each database then folds what we send onto the name it
     * stored. A quoted name is case-sensitive by definition and is passed through
     * verbatim, which also keeps {@code "t1"} and {@code t1} distinct.
     * <p>
     * {@code Locale.ROOT} is required: under a Turkish default locale
     * {@code "id".toUpperCase()} yields {@code "İD"}.
     */
    private static String normalizeIdentifier(String name) {
        return QS_PATTERN.matcher(name).matches() ? name : name.toUpperCase(Locale.ROOT);
    }

    /**
     * A loader with no ambient variables.
     */
    public Loader(MappingSpec ms, Connection connection) throws SQLException {
        this(ms, connection, Map.of());
    }

    /**
     * @param ms         the mapping spec whose record mappings this loader accepts
     * @param connection an open connection to the target database, supplied by the
     *                   application
     * @param ambient    application-provided values expressions can read, each
     *                   keyed under the reserved {@code xldr.} prefix (for example
     *                   {@code xldr.filename})
     */
    public Loader(MappingSpec ms, Connection connection, Map<String, Object> ambient) throws SQLException {
        this.mappingSpec = Objects.requireNonNull(ms);
        this.connection = Objects.requireNonNull(connection);
        this.ambient = Map.copyOf(ambient);
        connection.setAutoCommit(false);
        this.varValues = evaluateVars();
    }

    /**
     * Evaluates every input variable once, in declaration order, so a variable
     * may reference an earlier one. Each value is a plain object that a {@link
     * ValueSource.Var} then binds wherever it is referenced.
     */
    private Map<String, Object> evaluateVars() throws SQLException {
        var values = new LinkedHashMap<String, Object>();
        for (var v : mappingSpec.inputSpec().vars()) {
            values.put(v.name(), evaluate(v.source(), values));
        }
        return values;
    }

    private Object evaluate(ValueSource source, Map<String, Object> resolved) throws SQLException {
        return switch (source) {
            case ValueSource.Constant c -> c.value();
            case ValueSource.Var v -> {
                if (!resolved.containsKey(v.name())) {
                    throw new IllegalArgumentException(
                            "var '" + v.name() + "' is referenced before it is declared");
                }
                yield resolved.get(v.name());
            }
            case ValueSource.Lookup lk -> lookup(lk, evaluate(lk.key(), resolved));
            case ValueSource.Expr e -> Expression.compile(e.template()).eval(bindings(resolved, null));
            case ValueSource.Field _ -> throw new IllegalArgumentException(
                    "a var cannot read an input field: it is evaluated with no record in hand");
        };
    }

    /**
     * Bindings for an expression: {@code xldr.}-prefixed names come from the
     * ambient values, then declared variables, then - if a record is in scope -
     * the record's fields. {@code now()} yields an {@link Instant}; {@code
     * nextval(name[, start])} draws from an in-memory per-load sequence.
     */
    private Expression.Bindings bindings(Map<String, Object> vars, Row row) {
        return new Expression.Bindings() {
            @Override
            public Object variable(String name) {
                if (name.startsWith("xldr.")) {
                    if (!ambient.containsKey(name)) {
                        throw new IllegalArgumentException("unknown ambient variable: " + name);
                    }
                    return ambient.get(name);
                }
                if (vars.containsKey(name)) {
                    return vars.get(name);
                }
                if (row != null) {
                    return row.get(name);
                }
                throw new IllegalArgumentException(
                        "expression variable '" + name + "' is not a var, and no record is in scope");
            }

            @Override
            public Object function(String name, List<Object> args) {
                return switch (name) {
                    case "now" -> {
                        if (!args.isEmpty()) {
                            throw new IllegalArgumentException("now() takes no arguments");
                        }
                        yield Instant.now();
                    }
                    case "nextval" -> nextval(args);
                    default -> throw new IllegalArgumentException("unknown function: " + name);
                };
            }
        };
    }

    /**
     * The next value of an in-memory, per-load sequence. The first draw yields
     * the start value (default 1), each later one adds the increment (default
     * 1); {@code nextval(name, start, inc)} sets both. Sequences are shared by
     * name across the load.
     */
    private int nextval(List<Object> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof String name)) {
            throw new IllegalArgumentException("nextval(name[, start[, inc]]) needs a quoted sequence name");
        }
        int start = 1;
        int inc = 1;
        if (args.size() > 1) {
            if (!(args.get(1) instanceof Integer s)) {
                throw new IllegalArgumentException("nextval start must be an integer: " + args.get(1));
            }
            start = s;
            if(args.size()>2) {
                if (!(args.get(2) instanceof Integer t)) {
                    throw new IllegalArgumentException("nextval inc must be an integer: " + args.get(2));
                }
                inc = t;
            }
        }
        var finalInc = inc;
        return sequences.merge(name, start, (current, _) -> current + finalInc);
    }

    private Object lookup(ValueSource.Lookup lk, Object key) throws SQLException {
        var sql = "select " + normalizeIdentifier(lk.column())
                + " from " + normalizeIdentifier(lk.table())
                + " where " + normalizeIdentifier(lk.keyColumn()) + " = ?";
        try (var ps = connection.prepareStatement(sql)) {
            ps.setObject(1, key);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    /**
     * Parses {@code source} with the given adapter and inserts every record into
     * the table named by {@code mapping}.
     * <p>
     * A single adapter instance may be reused across all mappings of a file; the
     * caller supplies a freshly opened stream per call since a stream is read
     * only once.
     *
     * @param adapter the adapter for the input's MIME type
     * @param source  the input, read once by this call
     * @param mapping one of the record mappings of this loader's spec
     * @return the number of rows inserted
     * @throws IOException  if the input cannot be read
     * @throws SQLException if the database rejects a record, the message naming
     *                      which one
     */
    public int loadInput(InputAdapter adapter, InputStream source, RecordMappingSpec mapping)
            throws IOException, SQLException {
        Objects.requireNonNull(adapter);
        Objects.requireNonNull(source);
        if (!mappingSpec.recordMappingSpecs().contains(mapping)) {
            throw new IllegalArgumentException("mapping is not part of this loader's mapping spec: " + mapping);
        }
        try {
            var fieldMappings = mapping.fieldMappings()
                    .stream()
                    .filter(fm -> fm.source() != null && fm.databaseColumnName() != null)
                    .toList();
            if (fieldMappings.isEmpty()) {
                return 0;
            }

            var columns = new ArrayList<String>(fieldMappings.size());
            var valueExprs = new ArrayList<String>(fieldMappings.size());
            var binders = new ArrayList<Function<Row, Object>>();
            var fieldNames = new LinkedHashSet<String>();
            for (var fm : fieldMappings) {
                columns.add(fm.databaseColumnName());
                valueExprs.add(plan(fm.source(), binders, fieldNames));
            }

            var result = adapter.parse(source, mapping.recordSelector(), Set.copyOf(fieldNames));
            // the statement is not closed here - the cache owns it, and close()
            // closes them all once the load is over
            var ps = prepareInsert(new TabCol(mapping.databaseTable(), columns, valueExprs));

            var rowStream = result.rows();
            if (mapping.limit() != null) {
                rowStream = rowStream.limit(mapping.limit());
            }
            int count = 0;
            int pending = 0;
            // the records read so far, so that a failure can name the one that
            // caused it rather than leaving it to be counted out of the file
            int read = 0;
            try (var rows = rowStream) {
                var it = rows.iterator();
                while (it.hasNext()) {
                    try {
                        var row = it.next();
                        ps.clearParameters();
                        for (int i = 0; i < binders.size(); i++) {
                            var value = binders.get(i).apply(row);
                            if (value == null) {
                                ps.setNull(i + 1, Types.VARCHAR);
                            } else {
                                ps.setObject(i + 1, value);
                            }
                        }
                        ps.addBatch();
                    } catch (SQLException e) {
                        // reading or converting this one record, so it is known exactly
                        throw at(e, "record " + (read + 1), mapping);
                    } catch (RuntimeException e) {
                        throw at(e, "record " + (read + 1), mapping);
                    }
                    read++;
                    if (++pending == BATCH_SIZE) {
                        count += flush(ps, read - pending, pending, mapping);
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    count += flush(ps, read - pending, pending, mapping);
                }
            }
            return count;
        } catch (IOException | SQLException | RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    /**
     * Sends a batch, naming the record that failed if the driver says which one.
     * <p>
     * A {@link BatchUpdateException} reports one update count per statement it
     * got to: the failure is where {@link Statement#EXECUTE_FAILED} appears, or,
     * for a driver that stops at the first failure, one past the last count it
     * returned. A driver that says neither leaves only the range the batch
     * covered, which still beats searching the whole file.
     *
     * @param firstRecord the number of records read before this batch began
     * @param batched     how many records this batch carries
     */
    private static int flush(PreparedStatement ps, int firstRecord, int batched, RecordMappingSpec mapping)
            throws SQLException {
        try {
            return executeBatch(ps);
        } catch (BatchUpdateException e) {
            var failed = failedIndex(e);
            throw at(e, failed < 0
                            ? range(firstRecord, batched)
                            : "record " + (firstRecord + failed + 1),
                    mapping);
        } catch (SQLException e) {
            // the batch failed as a whole, without saying where
            throw at(e, range(firstRecord, batched), mapping);
        }
    }

    private static String range(int firstRecord, int batched) {
        return batched == 1
                ? "record " + (firstRecord + 1)
                : "records " + (firstRecord + 1) + " to " + (firstRecord + batched);
    }

    /**
     * The position of the failing statement within the batch, or -1 if the
     * driver did not say.
     */
    private static int failedIndex(BatchUpdateException e) {
        var counts = e.getUpdateCounts();
        if (counts == null) {
            return -1;
        }
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == Statement.EXECUTE_FAILED) {
                return i;
            }
        }
        // no failure marker: the driver stopped where its counts stop
        return counts.length;
    }

    /**
     * The same failure, saying which record and which mapping it happened in.
     * The kind is preserved where it carries meaning - a {@link SQLException}
     * keeps its SQL state and vendor code - so a caller that distinguishes them
     * still can.
     */
    private static RuntimeException at(RuntimeException e, String record, RecordMappingSpec mapping) {
        return new IllegalStateException(where(record, mapping) + ": " + e, e);
    }

    private static SQLException at(SQLException e, String record, RecordMappingSpec mapping) {
        return new SQLException(where(record, mapping) + ": " + e.getMessage(),
                e.getSQLState(), e.getErrorCode(), e);
    }

    private static String where(String record, RecordMappingSpec mapping) {
        return record + " of '" + mapping.recordSelector() + "' into " + mapping.databaseTable();
    }

    /**
     * Sends the rows collected so far and reports how many were inserted.
     * <p>
     * A driver may answer {@link Statement#SUCCESS_NO_INFO} rather than a count;
     * one insert is one row, so that counts as one. The batch is not the unit of
     * durability - the transaction is - so a failure here fails the whole load,
     * as an individual insert would have.
     */
    private static int executeBatch(PreparedStatement ps) throws SQLException {
        var counts = ps.executeBatch();
        int inserted = 0;
        for (var updated : counts) {
            if (updated == Statement.EXECUTE_FAILED) {
                // a driver may report the failure in the counts rather than by
                // throwing; carry them so the record can still be named
                throw new BatchUpdateException("an insert in the batch failed", counts);
            }
            inserted += updated == Statement.SUCCESS_NO_INFO ? 1 : updated;
        }
        return inserted;
    }

    private PreparedStatement prepareInsert(TabCol tabCol) throws SQLException {
        var cached = statementCache.get(tabCol);
        if (cached == null) {
            cached = connection.prepareStatement(tabCol.insertStatement());
            statementCache.put(tabCol, cached);
        }
        return cached;
    }

    /**
     * The value expression for one column, appending a binder (and, for a field,
     * the field name) as a side effect for every {@code ?} it introduces. The
     * order in which binders are appended matches the left-to-right order of the
     * {@code ?} in the generated SQL, which is what JDBC parameter numbering
     * follows - including a {@code ?} nested inside a lookup subquery.
     */
    private String plan(ValueSource source, List<Function<Row, Object>> binders, Set<String> fieldNames) {
        return switch (source) {
            case ValueSource.Field fld -> {
                fieldNames.add(fld.fieldName());
                binders.add(row -> row.get(fld.fieldName()));
                yield "?";
            }
            case ValueSource.Constant c -> {
                binders.add(_ -> c.value());
                yield "?";
            }
            case ValueSource.Var v -> {
                if (!varValues.containsKey(v.name())) {
                    throw new IllegalArgumentException("undefined var: " + v.name());
                }
                var value = varValues.get(v.name());
                binders.add(_ -> value);
                yield "?";
            }
            case ValueSource.Expr e -> {
                var expr = Expression.compile(e.template());
                // a name that is neither ambient nor a var is a field, so the
                // adapter has to be asked to resolve it
                for (var name : expr.variableNames()) {
                    if (!name.startsWith("xldr.") && !varValues.containsKey(name)) {
                        fieldNames.add(name);
                    }
                }
                binders.add(row -> expr.eval(bindings(varValues, row)));
                yield "?";
            }
            case ValueSource.Lookup lk -> {
                var keyExpr = plan(lk.key(), binders, fieldNames);
                yield "(select " + normalizeIdentifier(lk.column())
                        + " from " + normalizeIdentifier(lk.table())
                        + " where " + normalizeIdentifier(lk.keyColumn()) + " = " + keyExpr + ")";
            }
        };
    }

    /**
     * Commits the work of this loader - or rolls it back if any {@code loadInput}
     * call failed - releases the cached statements, restores the auto-commit
     * setting the connection had on arrival and closes it.
     */
    @Override
    public void close() throws SQLException {
        try {
            if (failed) {
                connection.rollback();
            } else {
                connection.commit();
            }
        } finally {
            try {
                for (var ps : statementCache.values()) {
                    try {
                        ps.close();
                    } catch (SQLException _) {
                    }
                }
                statementCache.clear();
            } finally {
                connection.close();
            }
        }
    }

}
