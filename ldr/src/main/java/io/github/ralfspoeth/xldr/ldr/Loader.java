package io.github.ralfspoeth.xldr.ldr;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.RecordMappingSpec;
import io.github.ralfspoeth.xldr.spec.ValueSource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

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
    /** compiled once per pattern rather than per row, the patterns coming from the spec */
    private final Map<String, DateTimeFormatter> formatters = new HashMap<>();
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
            requireNonNull(table);
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
     * Loads one whole input through one spec, as a single transaction.
     * <p>
     * The sequence is: find the adapter for the input spec, build one adapter for
     * the input - {@code parse} takes the record selector as a parameter, so there
     * is no reason to rebuild it, and for XML no reason to recompile every XPath -
     * and then run each record mapping over the input in turn, opening it again
     * for each because a stream is read once.
     * <p>
     * Everything or nothing: {@link #close()} commits at the end, or rolls back if
     * any mapping failed. The connection is closed either way, this method having
     * taken it over.
     * <p>
     * This is what both front ends do with an input once they have decided it is
     * ready - the file server having watched a file arrive, a web application
     * having read a request - so it lives here rather than in either of them.
     *
     * @param spec       the mapping spec; its input spec chooses the adapter
     * @param source     the input, openable once per record mapping
     * @param ambient    values expressions may read, under the reserved prefixes
     * @param connection an open connection, closed by this method
     * @return the total number of rows inserted, across every record mapping
     * @throws IllegalStateException if no adapter on the module path reads the
     *                               spec's MIME type
     */
    public static int load(MappingSpec spec, InputSource source,
                           Map<String, Object> ambient, Connection connection)
            throws IOException, SQLException {
        var factory = InputAdapterFactory.of(spec.inputSpec())
                .orElseThrow(() -> new IllegalStateException(
                        "no input adapter for mime type " + spec.inputSpec().mimeType()));
        var adapter = factory.createInputAdapter(spec.inputSpec());
        try (var loader = new Loader(spec, connection, ambient)) {
            int total = 0;
            for (var mapping : spec.recordMappingSpecs()) {
                try (var in = source.open()) {
                    total += loader.loadInput(adapter, in, mapping);
                }
            }
            return total;
        }
    }

    /**
     * @param ms         the mapping spec whose record mappings this loader accepts
     * @param connection an open connection to the target database, supplied by the
     *                   application
     * @param ambient    application-provided values expressions can read, each
     *                   keyed under one of the reserved prefixes: {@code xldr.}
     *                   for what the application knows about the load itself
     *                   (for example {@code xldr.filename}), {@code env.} for
     *                   what the deployment supplies (for example
     *                   {@code env.mandant}). The loader does not care where
     *                   either comes from; the prefixes exist so that a name in
     *                   an expression cannot be mistaken for a var or a field.
     */
    public Loader(MappingSpec ms, Connection connection, Map<String, Object> ambient) throws SQLException {
        this.mappingSpec = requireNonNull(ms);
        this.connection = requireNonNull(connection);
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
            values.put(v.name(), requireNonNull(evaluate(v.source(), values)));
        }
        return values;
    }

    private @Nullable Object evaluate(ValueSource source, Map<String, Object> resolved) throws SQLException {
        return switch (source) {
            case ValueSource.Constant c -> c.value();
            case ValueSource.Var v -> {
                if (!resolved.containsKey(v.name())) {
                    throw new IllegalArgumentException(
                            "var '" + v.name() + "' is referenced before it is declared");
                }
                yield resolved.get(v.name());
            }
            case ValueSource.Lookup lk -> lookup(lk, requireNonNull(evaluate(lk.key(), resolved)));
            case ValueSource.Expr e -> Expression.compile(e.template()).eval(bindings(resolved, null));
            case ValueSource.Field _ -> throw new IllegalArgumentException(
                    "a var cannot read an input field: it is evaluated with no record in hand");
        };
    }

    /**
     * The prefixes that send a name to the ambient values rather than to a var
     * or a field.
     * <p>
     * Reserved rather than merged for a reason: the fallback order is vars, then
     * fields, and an unprefixed ambient name would have to take a place in it.
     * Ahead of the fields it would shadow a column that happens to share its
     * name - silently, and in every row - and behind them it would be invisible
     * in exactly the mappings that have a record in scope. A prefix removes the
     * question rather than answering it.
     */
    private static final List<String> AMBIENT_PREFIXES = List.of("xldr.", "env.");

    /**
     * Bindings for an expression: names under a reserved prefix ({@code xldr.},
     * {@code env.}) come from the ambient values, then declared variables, then -
     * if a record is in scope - the record's fields. {@code now()} yields an {@link Instant};
     * {@code nextval(name[, start])} draws from an in-memory per-load sequence;
     * {@code format(value, pattern)} and {@code parse(text, pattern)} convert
     * between text and the date types.
     */
    private Expression.Bindings bindings(Map<String, Object> vars, @Nullable Row row) {
        return new Expression.Bindings() {
            @Override
            public @Nullable Object variable(String name) {
                if (AMBIENT_PREFIXES.stream().anyMatch(name::startsWith)) {
                    if (!ambient.containsKey(name)) {
                        // the names, never the values: an ambient map may hold
                        // things a log file should not
                        throw new IllegalArgumentException("unknown ambient variable '" + name
                                + "'; supplied are " + new TreeSet<>(ambient.keySet()));
                    }
                    return ambient.get(name);
                }
                if (vars.containsKey(name)) {
                    return vars.get(name);
                }
                return ofNullable(row)
                        .map(r->r.get(name))
                        .orElse(null);
            }

            @Override
            public @Nullable Object function(String name, List<@Nullable Object> args) {
                return switch (name) {
                    case "now" -> {
                        if (!args.isEmpty()) {
                            throw new IllegalArgumentException("now() takes no arguments");
                        }
                        yield Instant.now();
                    }
                    case "nextval" -> nextval(args);
                    case "format" -> format(args);
                    case "parse" -> parseTemporal(args);
                    default -> throw new IllegalArgumentException("unknown function: " + name);
                };
            }
        };
    }

    /**
     * {@code format(value, 'pattern')} - a date or timestamp as text, in the
     * pattern language of {@link DateTimeFormatter}.
     * <p>
     * This is the way to put a timestamp into a text column and know what it
     * will say: bind an instant to one and the driver renders it however it
     * likes, which under Oracle follows the session's NLS settings. An
     * {@link Instant} is rendered at the JVM's zone, having none of its own. A
     * null formats to null, so an absent date stays a SQL NULL rather than
     * becoming the text {@code "null"}.
     */
    private @Nullable String format(List<@Nullable Object> args) {
        if (args.size() != 2 || !(args.get(1) instanceof String pattern)) {
            throw new IllegalArgumentException(
                    "format(value, 'pattern') needs a value and a quoted pattern");
        }
        var value = args.getFirst();
        if (value == null) {
            return null;
        }
        if (!(value instanceof TemporalAccessor temporal)) {
            throw new IllegalArgumentException("format() takes a date or a timestamp, but got a "
                    + value.getClass().getSimpleName() + ": " + value);
        }
        return formatter(pattern).format(temporal);
    }

    /**
     * {@code parse(text, 'pattern')} - a date or timestamp read from text that
     * is in none of the notations an adapter recognises, for the one column that
     * needs it rather than for the whole feed the way {@code dateFormat} does.
     * <p>
     * The fields the pattern reads decide the type: a date and a time yield a
     * {@code LocalDateTime}, a date alone a {@code LocalDate}, a time alone a
     * {@code LocalTime} - all three types a driver has to bind. Blank text is an
     * absent value, as everywhere else, and yields null.
     */
    private @Nullable Object parseTemporal(List<@Nullable Object> args) {
        if (args.size() != 2 || !(args.get(1) instanceof String pattern)) {
            throw new IllegalArgumentException(
                    "parse(text, 'pattern') needs a text and a quoted pattern");
        }
        var value = args.getFirst();
        if (value == null) {
            return null;
        }
        var text = value.toString().strip();
        if (text.isEmpty()) {
            return null;
        }
        var parsed = formatter(pattern).parse(text);
        try {
            return LocalDateTime.from(parsed);
        } catch (DateTimeException _) {
            // the pattern read no time, or no date
        }
        try {
            return LocalDate.from(parsed);
        } catch (DateTimeException _) {
            // then a time is all it read, or the pattern reads neither
        }
        try {
            return LocalTime.from(parsed);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "parse('" + text + "', '" + pattern + "') yields neither a date nor a time", e);
        }
    }

    /**
     * The formatter for a pattern, compiled once. The zone is only consulted for
     * a value that has none - an {@link Instant} - and is the JVM's, the same
     * zone in which {@code now()} is read.
     */
    private DateTimeFormatter formatter(String pattern) {
        return formatters.computeIfAbsent(
                pattern, p -> DateTimeFormatter.ofPattern(p).withZone(ZoneId.systemDefault()));
    }

    /**
     * The next value of an in-memory, per-load sequence. The first draw yields
     * the start value (default 1), each later one adds the increment (default
     * 1); {@code nextval(name, start, inc)} sets both. Sequences are shared by
     * name across the load.
     */
    private int nextval(List<@Nullable Object> args) {
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

    /**
     * A value in a type every JDBC driver has to accept.
     * <p>
     * JDBC 4.2 lists the {@code java.time} types a driver must map -
     * {@code LocalDate}, {@code LocalTime}, {@code LocalDateTime},
     * {@code OffsetTime}, {@code OffsetDateTime} - and {@link Instant} is
     * deliberately not among them, an instant carrying no calendar to write into
     * a column. A driver may accept one, and several do; Oracle's rejects it
     * outright, before the type of the target column is even considered, so
     * {@code now()} would fail even against a text column. Converting here keeps
     * the instant exact and leaves {@code now()} returning what it says it does.
     *
     * @param value a value bound to a statement parameter, possibly {@code null}
     * @return the same value, or a JDBC-mandated type standing for it
     */
    private static @Nullable Object jdbcValue(@Nullable Object value) {
        return switch (value) {
            // the JVM's zone: the zone in which "now" was asked
            case Instant i -> OffsetDateTime.ofInstant(i, ZoneId.systemDefault());
            case ZonedDateTime z -> z.toOffsetDateTime();
            case null, default -> value;
        };
    }

    private @Nullable Object lookup(ValueSource.Lookup lk, @Nullable Object key) throws SQLException {
        if (key == null) {
            // = NULL is never true, so the query could only return nothing;
            // asking spares the round trip and the drivers that will not bind an
            // untyped null
            return null;
        }
        var sql = "select " + normalizeIdentifier(lk.column())
                + " from " + normalizeIdentifier(lk.table())
                + " where " + normalizeIdentifier(lk.keyColumn()) + " = ?";
        try (var ps = connection.prepareStatement(sql)) {
            ps.setObject(1, jdbcValue(key));
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
        requireNonNull(adapter);
        requireNonNull(source);
        if (!mappingSpec.recordMappingSpecs().contains(mapping)) {
            throw new IllegalArgumentException("mapping is not part of this loader's mapping spec: " + mapping);
        }
        try {
            if (mapping.fieldMappings().isEmpty()) {
                return 0;
            }

            var columns = new ArrayList<String>(mapping.fieldMappings().size());
            var valueExprs = new ArrayList<String>(mapping.fieldMappings().size());
            var binders = new ArrayList<Function<Row, @Nullable Object>>();
            var fieldNames = new LinkedHashSet<String>();
            for (var fm : mapping.fieldMappings()) {
                columns.add(fm.column());
                valueExprs.add(plan(fm.source(), binders, fieldNames));
            }

            var result = adapter.parse(source, mapping.recordSelector(), Set.copyOf(fieldNames));
            // the statement is not closed here - the cache owns it, and close()
            // closes them all once the load is over
            var ps = prepareInsert(new TabCol(mapping.table(), columns, valueExprs));

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
                                ps.setObject(i + 1, jdbcValue(value));
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
        return record + " of '" + mapping.recordSelector() + "' into " + mapping.table();
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
    private String plan(ValueSource source, List<Function<Row, @Nullable Object>> binders, Set<String> fieldNames) {
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
