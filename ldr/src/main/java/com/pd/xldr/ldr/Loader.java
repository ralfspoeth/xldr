package com.pd.xldr.ldr;

import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.spec.FieldMappingSpec;
import com.pd.xldr.spec.MappingSpec;
import com.pd.xldr.spec.RecordMappingSpec;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Inserts the records of a single input into the target database.
 * <p>
 * The connection is supplied by the caller - the application resolves the JNDI
 * name in {@code OutputSpec.dataSource()} and hands over
 * {@code dataSource.getConnection()}. The loader borrows it: it switches
 * auto-commit off for the duration of the load and {@link #close()} commits, or
 * rolls back if any {@code loadInput} call has failed, restores the previous
 * auto-commit setting and closes the connection - which returns it to its pool.
 * Intent is insert only.
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
    record TabCol(String table, List<String> columns) {
        TabCol {
            Objects.requireNonNull(table);
            table = normalizeName(table);
            columns = columns.stream().map(TabCol::normalizeName).toList();
        }

        /**
         * Unquoted SQL identifiers are case-insensitive in every target database;
         * they only disagree on the case they fold to - Oracle and H2 fold up,
         * PostgreSQL folds down. Folding to upper case here is portable because
         * we never add quotes: each database then folds what we send onto the
         * name it stored. A quoted name is case-sensitive by definition and is
         * passed through verbatim, which also keeps {@code "t1"} and {@code t1}
         * distinct cache entries - conservative, since whether they denote the
         * same table differs per database.
         * <p>
         * {@code Locale.ROOT} is required: under a Turkish default locale
         * {@code "id".toUpperCase()} yields {@code "İD"}.
         */
        private static String normalizeName(String name) {
            return QS_PATTERN.matcher(name).matches() ? name : name.toUpperCase(Locale.ROOT);
        }

        String insertStatement() {
            var columnList = String.join(", ", columns);
            var questionmarks = IntStream.range(0, columns.size())
                    .mapToObj(i -> "?")
                    .collect(Collectors.joining(", "));
            return String.format("insert into %s(%s) values(%s)", table, columnList, questionmarks);
        }
    }

    /**
     * @param ms         the mapping spec whose record mappings this loader accepts
     * @param connection an open connection to the target database, obtained by the
     *                   caller from the data source named in {@code ms.outputSpec()}
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
            // column order is driven by the field mappings, not by the order in
            // which the adapter happens to report its fields
            var fieldMappings = mapping.fieldMappings()
                    .stream()
                    .filter(fm -> fm.fieldName() != null && fm.databaseColumnName() != null)
                    .toList();
            if (fieldMappings.isEmpty()) {
                return 0;
            }
            var fieldNames = fieldMappings.stream().map(FieldMappingSpec::fieldName).toList();
            var columns = fieldMappings.stream().map(FieldMappingSpec::databaseColumnName).toList();

            var result = adapter.parse(source, mapping.recordSelector(), Set.copyOf(fieldNames));
            var ps = prepareInsert(new TabCol(mapping.databaseTable(), columns));

            int count = 0;
            try (var rows = result.rows()) {
                var it = rows.iterator();
                while (it.hasNext()) {
                    var row = it.next();
                    ps.clearParameters();
                    for (int i = 0; i < fieldNames.size(); i++) {
                        var value = row.get(fieldNames.get(i));
                        if (value == null) {
                            ps.setNull(i + 1, Types.VARCHAR);
                        } else {
                            ps.setObject(i + 1, value);
                        }
                    }
                    count += ps.executeUpdate();
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


    /*

    void generateImportHeader(String lfnr, boolean exec) throws SQLException {
        generateImportHeader(lfnr, exec, Optional.empty(), Optional.empty());
    }


    void generateImportHeader(String lfnr, boolean exec, Optional<String> sndef, Optional<String> inst) throws SQLException {
        var psImp = statementCache.computeIfAbsent(normalizeName("snlieferung"), (key) -> {
            try {
                return connection.prepareStatement("insert into snlieferung" +
                        "(lieferung_nr, schnittstelle_cd, institut_nr, neu_dat, syssnliefart_cd, syssnliefstatus_cd)" +
                        "values(?,?,?,?,?,?)");
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        });
        psImp.clearParameters();
        psImp.setString(1, Objects.requireNonNull(lfnr));
        psImp.setString(2, sndef.orElse(defaultSnDef()));
        psImp.setString(3, inst.orElse(defaultInstitut()));
        psImp.setDate(4, new Date(System.currentTimeMillis()));
        psImp.setString(5, "IMP");
        if (exec) {
            psImp.setString(6, "REDY");
        } else {
            psImp.setString(6, "WAIT");
        }
        int rows = psImp.executeUpdate();
        assert rows == 1;
    }

    void triggerImport(String lfnr, Optional<String> trigger) throws SQLException {
        var psEvent = statementCache.computeIfAbsent(normalizeName("snjobevent"), (key) -> {
            try {
                return connection.prepareStatement("insert into snjobevent(snjobevent_txt, par2_txt) values(?,?)");
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        });
        psEvent.clearParameters();
        psEvent.setString(1, trigger.orElse(defaultJobDef()));
        psEvent.setString(2, Objects.requireNonNull(lfnr));
        int rows = psEvent.executeUpdate();
        assert rows == 1;
    }

    void triggerImport(String lfnr) throws SQLException {
        triggerImport(lfnr, Optional.empty());
    } */

    public void insert(String table, List<String> cols, List<?> values) throws SQLException {
        var ps = prepareInsert(new TabCol(table, cols));
        ps.clearParameters();
        for (int i = 0; i < values.size(); i++) {
            var value = values.get(i);
            if (value == null) {
                ps.setNull(i + 1, Types.VARCHAR);
            } else {
                ps.setObject(i + 1, value);
            }
        }
        int rows = ps.executeUpdate();
        assert rows == 1;
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
                        // keep closing the remaining statements
                    }
                }
                statementCache.clear();
                // the connection was borrowed - hand it back as it was found,
                // a pool would otherwise lend it out with auto-commit still off
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                    // closing is more important than restoring
                }
            } finally {
                connection.close();
            }
        }
    }

/*
    String defaultInstitut() throws SQLException {
        String inr = null;
        try (var si = connection.createStatement(); var instituts = si.executeQuery("select institut_nr from institut where inaktiv_dat is null")) {
            if (instituts.next()) {
                inr = instituts.getString(1);
            }
        }
        return inr;
    }


    String defaultSnDef() throws SQLException {
        String sndef = null;
        try (var si = connection.createStatement(); var sndefs = si.executeQuery(
                "select schnittstelle_cd, default_flag from schnittstelle where inaktiv_dat is null order by nvl(sort_no, 9999)"
        )) {
            while (sndefs.next()) {
                if (1 == sndefs.getInt(2)) {
                    sndef = sndefs.getString(1);
                    break;
                }
                if (sndef == null) {
                    sndef = sndefs.getString(1);
                }
            }
        }
        return sndef;
    }

    String defaultJobDef() throws SQLException {
        String jobdef = null;
        try (var si = connection.createStatement(); var jobdefs = si.executeQuery(
                "select snjobevent_txt from jobdef where nvl(inaktiv_flag,0) = 0 and SYSJOBFREQ_ID = hextoraw('1051000083000002') and snjobevent_txt is not null"
        )) {
            if (jobdefs.next()) {
                jobdef = jobdefs.getString(1);
            }
        }
        return jobdef;
    } */

}
