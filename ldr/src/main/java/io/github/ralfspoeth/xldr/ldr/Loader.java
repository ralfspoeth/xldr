package io.github.ralfspoeth.xldr.ldr;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.ColumnSource;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.RecordMappingSpec;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
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
 * any record mapping failed - then restores the previous auto-commit setting and
 * closes the connection, which returns it to its pool. Intent is insert only.
 */
public class Loader implements AutoCloseable {
    private static final Pattern QS_PATTERN = Pattern.compile("\".*\"");

    private final MappingSpec mappingSpec;
    private final Connection connection;
    private final boolean autoCommit;
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
     * @param ms         the mapping spec whose record mappings this loader accepts
     * @param connection an open connection to the target database, supplied by the
     *                   application
     */
    public Loader(MappingSpec ms, Connection connection) throws SQLException {
        this.mappingSpec = Objects.requireNonNull(ms);
        this.connection = Objects.requireNonNull(connection);
        this.autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
    }

    /**
     * Parses {@code source} with the given adapter and inserts every record into
     * the table named by {@code mapping}.
     * <p>
     * A single adapter instance may be reused across all mappings of a file; the
     * caller supplies a freshly opened stream per call since a stream is read
     * only once.
     *
     * @return the number of rows inserted
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
            int count;
            try (var ps = prepareInsert(new TabCol(mapping.databaseTable(), columns, valueExprs))) {

                var rowStream = result.rows();
                if (mapping.limit() != null) {
                    rowStream = rowStream.limit(mapping.limit());
                }
                count = 0;
                try (var rows = rowStream) {
                    var it = rows.iterator();
                    while (it.hasNext()) {
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
                        count += ps.executeUpdate();
                    }
                }
            }
            return count;
        } catch (IOException | SQLException | RuntimeException e) {
            failed = true;
            throw e;
        }
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
    private static String plan(ColumnSource source, List<Function<Row, Object>> binders, Set<String> fieldNames) {
        return switch (source) {
            case ColumnSource.Function f -> f.sql();
            case ColumnSource.Field fld -> {
                fieldNames.add(fld.fieldName());
                binders.add(row -> row.get(fld.fieldName()));
                yield "?";
            }
            case ColumnSource.Constant c -> {
                binders.add(_ -> c.value());
                yield "?";
            }
            case ColumnSource.Lookup lk -> {
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
                    } catch (SQLException ignored) {
                    }
                }
                statementCache.clear();
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                }
            } finally {
                connection.close();
            }
        }
    }

}
