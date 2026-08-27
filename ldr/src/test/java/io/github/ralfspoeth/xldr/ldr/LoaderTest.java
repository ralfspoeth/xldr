package io.github.ralfspoeth.xldr.ldr;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.*;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;


class LoaderTest {

    private String jdbcUrl;


    @BeforeEach
    void prepareConn() throws SQLException {
        jdbcUrl = ResourceBundle.getBundle("h2").getString("url");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists person");
            stmt.execute("create table person(id varchar(10), name varchar(50), city varchar(50))");
        }
    }

    /**
     * Two mappings target the same table with different column sets. Each has to
     * contribute its own rows, and each insert must mention only its own columns
     * so the remaining ones stay null rather than being forced to null.
     */
    @Test
    void loadsSeveralMappingsIntoTheSameTable() throws Exception {
        // column order deliberately differs from the natural one: name before id
        var people = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec("name", new ValueSource.Field("name")),
                new FieldMappingSpec("id", new ValueSource.Field("id"))
        ), null);
        // same table, spelled in upper case, and a different set of columns
        var visitors = new RecordMappingSpec("visitors", "PERSON", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("city", new ValueSource.Field("city"))
        ), null);
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of(), List.of(), Map.of()),
                List.of(people, visitors)
        );

        var adapter = adapterFor(Map.of(
                "people", List.of(
                        Map.of("id", "1", "name", "Alice"),
                        Map.of("id", "2", "name", "Bob")
                ),
                "visitors", List.of(
                        Map.of("id", "3", "city", "Berlin"),
                        Map.of("id", "4")   // no city at all -> null
                )
        ));

        int inserted;
        try (var loader = createLoader(spec)) {
            inserted = loader.loadInput(adapter, InputStream.nullInputStream(), people)
                    + loader.loadInput(adapter, InputStream.nullInputStream(), visitors);
        }
        assertEquals(4, inserted);

        assertEquals(
                List.of(
                        Arrays.asList("1", "Alice", null),
                        Arrays.asList("2", "Bob", null),
                        Arrays.asList("3", null, "Berlin"),
                        Arrays.asList("4", null, null)
                ),
                selectPersons()
        );
    }

    private @NonNull Loader createLoader(MappingSpec spec) throws SQLException {
        return new Loader(spec, DriverManager.getConnection(jdbcUrl), Map.of());
    }

    /**
     * More records than fit in one batch: every row still arrives, exactly once,
     * and the reported count is the number of rows rather than the number of
     * batches. The count deliberately straddles a batch boundary so that a
     * dropped or double-sent final partial batch would show.
     */
    @Test
    void loadsMoreRecordsThanOneBatch() throws Exception {
        var mapping = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("name", new ValueSource.Field("name"))
        ), null);
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of(), List.of(), Map.of()),
                List.of(mapping)
        );

        int records = 2_500;
        var people = new ArrayList<Map<String, String>>(records);
        for (int i = 0; i < records; i++) {
            people.add(Map.of("id", String.valueOf(i), "name", "p" + i));
        }
        var adapter = adapterFor(Map.of("people", people));

        int inserted;
        try (var loader = createLoader(spec)) {
            inserted = loader.loadInput(adapter, InputStream.nullInputStream(), mapping);
        }
        assertEquals(records, inserted);

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select count(*), count(distinct id) from person")) {
            assertTrue(rs.next());
            assertEquals(records, rs.getInt(1), "every record inserted");
            assertEquals(records, rs.getInt(2), "and none of them twice");
        }
    }

    /**
     * Two mappings with the same target table and the same columns share one
     * prepared statement, which the loader caches - so the first must not leave
     * it closed behind it.
     */
    @Test
    void reusesTheStatementOfTwoIdenticalMappings() throws Exception {
        var columns = List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("name", new ValueSource.Field("name")));
        var first = new RecordMappingSpec("people", "person", columns, null);
        var second = new RecordMappingSpec("visitors", "person", columns, null);
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of(), List.of(), Map.of()),
                List.of(first, second)
        );

        var adapter = adapterFor(Map.of(
                "people", List.of(Map.of("id", "1", "name", "Alice")),
                "visitors", List.of(Map.of("id", "2", "name", "Bob"))));

        try (var loader = createLoader(spec)) {
            assertEquals(1, loader.loadInput(adapter, InputStream.nullInputStream(), first));
            assertEquals(1, loader.loadInput(adapter, InputStream.nullInputStream(), second));
        }

        assertEquals(
                List.of(Arrays.asList("1", "Alice", null), Arrays.asList("2", "Bob", null)),
                selectPersons());
    }

    /**
     * A record the database rejects is named in the failure, so the file does
     * not have to be counted through by hand to find it. The bad record sits
     * well inside a batch, which is the case that would otherwise only be
     * locatable to within a thousand records.
     */
    @Test
    void namesTheRecordThatFailed() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists narrow");
            stmt.execute("create table narrow(id varchar(3))");
        }
        var mapping = new RecordMappingSpec("people", "narrow", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id"))
        ), null);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));

        // the 7th record is too long for the column; the others fit
        var people = new ArrayList<Map<String, String>>();
        for (int i = 1; i <= 20; i++) {
            people.add(Map.of("id", i == 7 ? "far too long" : String.valueOf(i)));
        }
        var adapter = adapterFor(Map.of("people", people));

        var thrown = assertThrows(SQLException.class, () -> {
            try (var loader = createLoader(spec)) {
                loader.loadInput(adapter, InputStream.nullInputStream(), mapping);
            }
        });
        assertTrue(thrown.getMessage().contains("record 7"),
                "should name the record, was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("narrow"),
                "and the mapping, was: " + thrown.getMessage());
    }

    /**
     * A record the adapter itself cannot produce - a value that will not convert
     * - is named just as one the database rejects.
     */
    @Test
    void namesTheRecordThatCouldNotBeRead() throws Exception {
        var mapping = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id"))
        ), null);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));

        // the adapter throws while producing the third record
        InputAdapter adapter = (source, recordSelector, fieldSelectors) -> new Result(
                List.of(new Field("id", String.class)),
                Stream.of(1, 2, 3).map(i -> name -> {
                    if (i == 3) {
                        throw new IllegalArgumentException("not a number: xyz");
                    }
                    return String.valueOf(i);
                }));

        var thrown = assertThrows(RuntimeException.class, () -> {
            try (var loader = createLoader(spec)) {
                loader.loadInput(adapter, InputStream.nullInputStream(), mapping);
            }
        });
        assertTrue(thrown.getMessage().contains("record 3"),
                "should name the record, was: " + thrown.getMessage());
    }

    /**
     * A failing mapping has to take the whole load down with it - close() rolls
     * back instead of committing.
     */
    @Test
    void rollsBackWhenAMappingFails() throws Exception {
        var good = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id"))
        ), null);
        var broken = new RecordMappingSpec("people", "no_such_table", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id"))
        ), null);
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of(), List.of(), Map.of()),
                List.of(good, broken)
        );
        var adapter = adapterFor(Map.of("people", List.of(Map.of("id", "1"))));

        assertThrows(SQLException.class, () -> {
            try (var loader = createLoader(spec)) {
                loader.loadInput(adapter, InputStream.nullInputStream(), good);
                loader.loadInput(adapter, InputStream.nullInputStream(), broken);
            }
        });

        // the row inserted by the good mapping must not have survived
        assertEquals(List.of(), selectPersons());
    }

    /**
     * A mapping that does not belong to the loader's own spec is rejected.
     */
    @Test
    void rejectsForeignMapping() throws Exception {
        var known = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id"))
        ), null);
        var foreign = new RecordMappingSpec("elsewhere", "person", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id"))
        ), null);
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of(), List.of(), Map.of()),
                List.of(known)
        );
        var adapter = adapterFor(Map.of());

        try (var loader = createLoader(spec)) {
            assertThrows(IllegalArgumentException.class,
                    () -> loader.loadInput(adapter, InputStream.nullInputStream(), foreign));
        }
    }

    /**
     * A field value and a spec constant share one insert: the field is bound from
     * the row, the constant from the spec, and every row carries it.
     */
    @Test
    void insertsFieldsAndConstants() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists event");
            stmt.execute("create table event(id varchar(10), source varchar(10))");
        }
        var mapping = new RecordMappingSpec("events", "event", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("source", new ValueSource.Constant("PD"))
        ), null);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("events", List.of(Map.of("id", "1"), Map.of("id", "2"))));

        try (var loader = createLoader(spec)) {
            assertEquals(2, loader.loadInput(adapter, InputStream.nullInputStream(), mapping));
        }

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select id, source from event order by id")) {
            var rows = new ArrayList<List<Object>>();
            while (rs.next()) {
                rows.add(Arrays.asList(rs.getString(1), rs.getString(2)));
            }
            assertEquals(2, rows.size());
            assertAll(
                    () -> assertEquals("1", rows.get(0).get(0)),
                    () -> assertEquals("PD", rows.get(0).get(1)),
                    () -> assertEquals("2", rows.get(1).get(0)),
                    () -> assertEquals("PD", rows.get(1).get(1))
            );
        }
    }

    /**
     * A var is evaluated once per load and shared by every row - here a value
     * looked up from a reference table and stamped onto each row.
     */
    @Test
    void bindsALookupVarToEveryRow() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists batch");
            stmt.execute("drop table if exists batch_src");
            stmt.execute("create table batch_src(k int, v bigint)");
            stmt.execute("insert into batch_src values (1, 42)");
            stmt.execute("create table batch(id varchar(10), batch_id bigint)");
        }
        var vars = List.of(new VarSpec("bid",
                new ValueSource.Lookup("batch_src", "v", "k", new ValueSource.Constant(1))));
        var mapping = new RecordMappingSpec("rows", "batch", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("batch_id", new ValueSource.Var("bid"))
        ), null);
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of(), vars, Map.of()),
                List.of(mapping));
        var adapter = adapterFor(Map.of("rows", List.of(
                Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3"))));

        try (var loader = createLoader(spec)) {
            assertEquals(3, loader.loadInput(adapter, InputStream.nullInputStream(), mapping));
        }

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select batch_id from batch")) {
            var ids = new ArrayList<Long>();
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
            // the looked-up value is shared by all three rows
            assertEquals(List.of(42L, 42L, 42L), ids);
        }
    }

    /**
     * An expression var interpolates an ambient value and an in-memory sequence,
     * evaluated once, so every row carries the same generated id.
     */
    @Test
    void expressionGeneratesAnIdOncePerLoad() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists doc");
            stmt.execute("create table doc(id varchar(10), gen varchar(40))");
        }
        var vars = List.of(new VarSpec("gid",
                new ValueSource.Expr("${xldr.filename}-${nextval('batch')}")));
        var mapping = new RecordMappingSpec("rows", "doc", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("gen", new ValueSource.Var("gid"))
        ), null);
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of(), vars, Map.of()),
                List.of(mapping));
        var adapter = adapterFor(Map.of("rows", List.of(Map.of("id", "1"), Map.of("id", "2"))));

        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl),
                Map.of("xldr.filename", "orders.csv"))) {
            loader.loadInput(adapter, InputStream.nullInputStream(), mapping);
        }

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select gen from doc")) {
            var gens = new ArrayList<String>();
            while (rs.next()) {
                gens.add(rs.getString(1));
            }
            assertEquals(List.of("orders.csv-1", "orders.csv-1"), gens);
        }
    }

    /**
     * A per-row expression draws a fresh sequence value for each record; a single
     * {@code nextval} placeholder keeps its integer type.
     */
    @Test
    void expressionNumbersRowsWithNextval() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists seq_rows");
            stmt.execute("create table seq_rows(id varchar(10), n integer)");
        }
        var mapping = new RecordMappingSpec("rows", "seq_rows", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("n", new ValueSource.Expr("${nextval('r', 10)}"))
        ), null);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("rows", List.of(
                Map.of("id", "a"), Map.of("id", "b"), Map.of("id", "c"))));

        try (var loader = createLoader(spec)) {
            assertEquals(3, loader.loadInput(adapter, InputStream.nullInputStream(), mapping));
        }

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select n from seq_rows order by n")) {
            var ns = new ArrayList<Integer>();
            while (rs.next()) {
                ns.add(rs.getInt(1));
            }
            assertEquals(List.of(10, 11, 12), ns);
        }
    }

    /**
     * {@code format} renders a timestamp into a text column as the pattern says,
     * rather than however the driver would render one - the reason the function
     * exists. It takes a call as its argument, which the parser has to see
     * through: the comma inside the quoted pattern is part of the pattern, not a
     * separator.
     */
    @Test
    void expressionFormatsATimestampAsText() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists stamped");
            stmt.execute("create table stamped(id varchar(10), loaded_at varchar(40), weekday varchar(60))");
        }
        var mapping = new RecordMappingSpec("rows", "stamped", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("loaded_at", new ValueSource.Expr("${format(now(), 'yyyy-MM-dd')}")),
                // a comma inside the pattern is the pattern's, not an argument separator
                new FieldMappingSpec("weekday", new ValueSource.Expr("${format(now(), 'EEE, dd MMM yyyy')}"))
        ), null);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("rows", List.of(Map.of("id", "1"))));

        try (var loader = createLoader(spec)) {
            loader.loadInput(adapter, InputStream.nullInputStream(), mapping);
        }

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select loaded_at, weekday from stamped")) {
            assertTrue(rs.next());
            assertEquals(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), rs.getString(1));
            assertTrue(rs.getString(2).contains(","), "the whole pattern was used: " + rs.getString(2));
        }
    }

    /**
     * {@code parse} reads a field written in a notation no adapter recognises,
     * for the one column that needs it, and binds it as a date the driver
     * understands. A field named inside the call is requested from the adapter
     * just as a bare reference would be.
     */
    @Test
    void expressionParsesAFieldIntoADate() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists dated");
            stmt.execute("create table dated(id varchar(10), born date)");
        }
        var mapping = new RecordMappingSpec("rows", "dated", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("born", new ValueSource.Expr("${parse(birthdate, 'dd.MM.yyyy')}"))
        ), null);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("rows", List.of(
                Map.of("id", "1", "birthdate", "07.03.1975"))));

        try (var loader = createLoader(spec)) {
            loader.loadInput(adapter, InputStream.nullInputStream(), mapping);
        }

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select born from dated")) {
            assertTrue(rs.next());
            assertEquals(LocalDate.of(1975, 3, 7), rs.getObject(1, LocalDate.class));
        }
    }

    /**
     * A null argument is still an argument. {@code format} and {@code parse}
     * both promise that an absent value yields null rather than the text
     * {@code "null"}, and they can only keep that promise if the null reaches
     * them: dropping it would leave the pattern as the only argument, and the
     * call would be refused for the arity the author did in fact write.
     */
    @Test
    void expressionFunctionsSeeANullArgument() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists absent");
            stmt.execute("create table absent(id varchar(10), born date, shown varchar(40))");
        }
        var mapping = new RecordMappingSpec("rows", "absent", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("born", new ValueSource.Expr("${parse(birthdate, 'dd.MM.yyyy')}")),
                new FieldMappingSpec("shown", new ValueSource.Expr("${format(birthdate, 'yyyy')}"))
        ), null);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));
        // the row carries no birthdate at all, so both calls resolve theirs to null
        var adapter = adapterFor(Map.of("rows", List.of(Map.of("id", "1"))));

        try (var loader = createLoader(spec)) {
            assertEquals(1, loader.loadInput(adapter, InputStream.nullInputStream(), mapping));
        }

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select born, shown from absent")) {
            assertTrue(rs.next());
            assertNull(rs.getObject(1), "an absent date parses to SQL NULL");
            assertNull(rs.getString(2), "an absent date formats to SQL NULL, not to \"null\"");
        }
    }

    /**
     * The optional increment steps the sequence by more than one, while the
     * start still governs the first draw.
     */
    @Test
    void expressionSequenceHonoursStartAndIncrement() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists inc_rows");
            stmt.execute("create table inc_rows(id varchar(10), n integer)");
        }
        var mapping = new RecordMappingSpec("rows", "inc_rows", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("n", new ValueSource.Expr("${nextval('r', 100, 5)}"))
        ), null);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("rows", List.of(
                Map.of("id", "a"), Map.of("id", "b"), Map.of("id", "c"), Map.of("id", "d"))));

        try (var loader = createLoader(spec)) {
            assertEquals(4, loader.loadInput(adapter, InputStream.nullInputStream(), mapping));
        }

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select n from inc_rows order by n")) {
            var ns = new ArrayList<Integer>();
            while (rs.next()) {
                ns.add(rs.getInt(1));
            }
            assertEquals(List.of(100, 105, 110, 115), ns);
        }
    }

    /**
     * A lookup with no conditions reads the whole table, which is how a
     * single-row view or Oracle's {@code dual} is read.
     * <p>
     * Against a database because the thing that can go wrong is the SQL text: an
     * empty condition list must drop the {@code where} rather than emit one with
     * nothing after it, and a dangling {@code where} is a syntax error no unit
     * test on the record would see.
     */
    @Test
    void resolvesALookupOnNoColumnsAtAll() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists current_rate");
            stmt.execute("drop table if exists stamped_rate");
            stmt.execute("create table current_rate(factor int)");
            stmt.execute("insert into current_rate values (99)");
            stmt.execute("create table stamped_rate(sku varchar(10), factor int)");
        }
        var mapping = new RecordMappingSpec("lines", "stamped_rate", List.of(
                new FieldMappingSpec("sku", new ValueSource.Field("sku")),
                new FieldMappingSpec("factor", new ValueSource.Lookup(
                        "current_rate", "factor", new LinkedHashMap<>()))
        ), null);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("lines", List.of(
                Map.of("sku", "S1"), Map.of("sku", "S2"))));

        try (var loader = createLoader(spec)) {
            assertEquals(2, loader.loadInput(adapter, InputStream.nullInputStream(), mapping));
        }

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select sku, factor from stamped_rate order by sku")) {
            var rows = new ArrayList<List<Object>>();
            while (rs.next()) {
                rows.add(Arrays.asList(rs.getString(1), rs.getObject(2)));
            }
            assertEquals(List.of(Arrays.asList("S1", 99), Arrays.asList("S2", 99)), rows);
        }
    }

    /**
     * A lookup may match on several columns, and then the conditions are
     * {@code and}ed in the order they were written.
     * <p>
     * Against a real database rather than against the SQL string, because the
     * thing that can go wrong is not the text: it is the binding. The clause and
     * the parameters are built by one loop over one ordered map, and if those
     * two ever came apart the rows would still load - with the values swapped
     * between the columns, matching whatever they happened to match. Two
     * conditions of the same type is the case that would hide it, so both here
     * are text.
     */
    @Test
    void resolvesALookupOnSeveralColumns() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists rate");
            stmt.execute("drop table if exists priced");
            stmt.execute("create table rate(ccy varchar(3), asof varchar(10), factor int)");
            stmt.execute("""
                    insert into rate values
                        ('EUR', '2026-01-01', 10), ('EUR', '2026-02-01', 20),
                        ('USD', '2026-01-01', 30), ('USD', '2026-02-01', 40)""");
            stmt.execute("create table priced(sku varchar(10), factor int)");
        }
        var conditions = new LinkedHashMap<SqlIdentifier, ValueSource>();
        conditions.put(new SqlIdentifier("ccy"), new ValueSource.Field("currency"));
        conditions.put(new SqlIdentifier("asof"), new ValueSource.Field("day"));
        var mapping = new RecordMappingSpec("lines", "priced", List.of(
                new FieldMappingSpec("sku", new ValueSource.Field("sku")),
                new FieldMappingSpec("factor", new ValueSource.Lookup("rate", "factor", conditions))
        ), null);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("lines", List.of(
                Map.of("sku", "S1", "currency", "EUR", "day", "2026-02-01"),
                Map.of("sku", "S2", "currency", "USD", "day", "2026-01-01"),
                // the pair that exists in neither combination, though each value
                // of it appears in the table on its own
                Map.of("sku", "S3", "currency", "EUR", "day", "2026-03-01")
        )));

        try (var loader = createLoader(spec)) {
            assertEquals(3, loader.loadInput(adapter, InputStream.nullInputStream(), mapping));
        }

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select sku, factor from priced order by sku")) {
            var rows = new ArrayList<List<Object>>();
            while (rs.next()) {
                rows.add(Arrays.asList(rs.getString(1), rs.getObject(2)));
            }
            assertEquals(List.of(
                    Arrays.asList("S1", 20),
                    Arrays.asList("S2", 30),
                    Arrays.asList("S3", null)
            ), rows);
        }
    }

    /**
     * A lookup resolves a column from a reference table via an inline subquery,
     * keyed here by an input field. A key that matches no row yields SQL NULL.
     */
    @Test
    void resolvesLookups() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists country");
            stmt.execute("drop table if exists holder");
            stmt.execute("create table country(iso varchar(2), id int)");
            stmt.execute("insert into country values ('DE', 49), ('US', 1)");
            stmt.execute("create table holder(name varchar(20), country_id int)");
        }
        var mapping = new RecordMappingSpec("holders", "holder", List.of(
                new FieldMappingSpec("name", new ValueSource.Field("name")),
                new FieldMappingSpec(
                        "country_id", new ValueSource.Lookup("country", "id", "iso", new ValueSource.Field("c"))
                )
        ), null);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("holders", List.of(
                Map.of("name", "Alice", "c", "DE"),
                Map.of("name", "Bob", "c", "US"),
                Map.of("name", "Carol", "c", "ZZ")   // no such country -> null
        )));

        try (var loader = createLoader(spec)) {
            assertEquals(3, loader.loadInput(adapter, InputStream.nullInputStream(), mapping));
        }

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select name, country_id from holder order by name")) {
            var rows = new ArrayList<List<Object>>();
            while (rs.next()) {
                rows.add(Arrays.asList(rs.getString(1), rs.getObject(2)));
            }
            assertEquals(List.of(
                    Arrays.asList("Alice", 49),
                    Arrays.asList("Bob", 1),
                    Arrays.asList("Carol", null)
            ), rows);
        }
    }

    /**
     * A record mapping with a limit inserts at most that many rows.
     */
    @Test
    void honoursTheRowLimit() throws Exception {
        var mapping = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("name", new ValueSource.Field("name"))
        ), 2);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("people", List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob"),
                Map.of("id", "3", "name", "Carol"),
                Map.of("id", "4", "name", "Dave")
        )));

        try (var loader = createLoader(spec)) {
            assertEquals(2, loader.loadInput(adapter, InputStream.nullInputStream(), mapping));
        }
        assertEquals(List.of("1:Alice", "2:Bob"),
                selectPersons().stream().map(r -> r.get(0) + ":" + r.get(1)).toList());
    }

    /**
     * {@code ${now()}} into a {@code timestamp with time zone}, which is what
     * tutorial pages 5 and 7 tell a reader to write and what nothing checked
     * while they said it.
     * <p>
     * The claim has two halves and both can fail quietly. {@code now()} yields an
     * {@link java.time.Instant}, which JDBC 4.2 deliberately does not list among
     * the {@code java.time} types a driver must map - so the loader converts it
     * to an {@code OffsetDateTime} at the JVM's zone on the way to the statement.
     * If that conversion were dropped the value would still reach some drivers
     * and be refused by others, which is the kind of thing that works in a test
     * and fails in production against Oracle.
     * <p>
     * The other half is the column type. Into a zoned column the instant keeps
     * its offset and comes back as the same moment; a plain {@code timestamp}
     * would silently take the zone from wherever it was read, which is why the
     * tutorial says to declare the column with one.
     */
    @Test
    void nowReachesAzonedColumnAsTheSameInstant() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists arrival");
            stmt.execute("create table arrival(id varchar(10), loaded_at timestamp with time zone)");
        }

        var mapping = new RecordMappingSpec("rows", "arrival", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("loaded_at", new ValueSource.Expr("${now()}"))
        ), null);
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of(), List.of(), Map.of()),
                List.of(mapping));

        var before = Instant.now();
        try (var loader = createLoader(spec)) {
            loader.loadInput(adapterFor(Map.of("rows", List.of(Map.of("id", "1")))),
                    InputStream.nullInputStream(), mapping);
        }
        var after = Instant.now();

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select loaded_at from arrival")) {
            assertTrue(rs.next(), "one row");
            var loadedAt = rs.getObject(1, OffsetDateTime.class);
            assertAll(
                    () -> assertNotNull(loadedAt, "the column is not null"),
                    () -> assertNotNull(loadedAt.getOffset(), "and carries an offset"),
                    // the instant, not the local reading of it: compare on the
                    // timeline so that a wrong offset shows up rather than
                    // cancelling out against the JVM's own zone
                    () -> assertFalse(loadedAt.toInstant().isBefore(before.minusSeconds(1)),
                            "loaded before the load started: " + loadedAt),
                    () -> assertFalse(loadedAt.toInstant().isAfter(after.plusSeconds(1)),
                            "loaded after the load finished: " + loadedAt));
            assertFalse(rs.next(), "exactly one row");
        }
    }

    /**
     * A target's schema reaches the insert, and the lookup that feeds it.
     * <p>
     * The point of the whole arrangement: the same spec, unchanged, loads into
     * whichever schema the deployment names. So this creates two schemas with the
     * same table in each, loads with the target pointed at one of them, and
     * checks the rows landed there and not in the other - which an unqualified
     * insert against a connection whose search path finds either would get wrong
     * silently.
     * <p>
     * The lookup is here because it is the easier half to forget: its select is
     * built separately from the insert, so a target applied to one and not the
     * other would pass every test that only inserts.
     */
    @Test
    void loadsIntoTheSchemaTheTargetNames() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            for (var schema : List.of("staging", "elsewhere")) {
                stmt.execute("drop schema if exists " + schema + " cascade");
                stmt.execute("create schema " + schema);
                stmt.execute("create table " + schema + ".person(id varchar(10), name varchar(50))");
                stmt.execute("create table " + schema + ".naming(code varchar(2), label varchar(20))");
            }
            stmt.execute("insert into staging.naming values ('DE', 'from-staging')");
            stmt.execute("insert into elsewhere.naming values ('DE', 'from-elsewhere')");
        }

        var mapping = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id")),
                new FieldMappingSpec("name",
                        new ValueSource.Lookup("naming", "label", "code",
                                new ValueSource.Constant("DE")))
        ), null);
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));

        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl),
                Map.of(), new Target(null, "staging"))) {
            loader.loadInput(adapterFor(Map.of("people", List.of(Map.of("id", "1")))),
                    InputStream.nullInputStream(), mapping);
        }

        assertAll(
                () -> assertEquals(List.of("1:from-staging"), personsIn("staging"),
                        "the rows, and the lookup, went to the schema the target named"),
                () -> assertEquals(List.of(), personsIn("elsewhere"),
                        "and nothing went anywhere else"));
    }

    /**
     * The shapes a qualified name comes in, checked by loading through each and
     * seeing where the row landed.
     * <p>
     * Here rather than as a unit test of a {@code qualify} method, because the
     * question a qualifier answers - will this database take a schema in an
     * insert, and how is one written - is a question about a database. A pure
     * test of the string-building would have agreed with itself.
     * <p>
     * <strong>Only H2 answers here.</strong> Every driver this project ships
     * puts the catalog first and separates with a dot, so that is all the build
     * exercises; a driver that answered otherwise would have no test, which is
     * the price of putting the rendering behind a {@link java.sql.Connection}.
     */
    @Test
    void qualifiesWithWhicheverPartsTheTargetHas() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop schema if exists mixed cascade");
            stmt.execute("create schema mixed");
            stmt.execute("create table mixed.person(id varchar(10), name varchar(50))");
        }
        var mapping = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id"))), null);
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));

        // the two spellings of one unquoted schema are one schema
        for (var schema : List.of("mixed", "MIXED")) {
            try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl),
                    Map.of(), new Target(null, schema))) {
                loader.loadInput(adapterFor(Map.of("people", List.of(Map.of("id", schema)))),
                        InputStream.nullInputStream(), mapping);
            }
        }
        assertEquals(2, personsIn("mixed").size(), "both spellings reached the same table");
    }

    /**
     * A target this database will not honour stops the load before it starts.
     * <p>
     * H2 takes both, so the refusal is provoked through a schema that is not
     * there rather than through a database that refuses the concept - the
     * PostgreSQL case, where {@code supportsCatalogsInDataManipulation} is
     * false, has no driver in this build to exercise it.
     */
    @Test
    void aschemaThatIsNotThereFailsTheLoadRatherThanTheRow() throws Exception {
        var mapping = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec("id", new ValueSource.Field("id"))), null);
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of(), List.of(), Map.of()), List.of(mapping));

        assertThrows(SQLException.class, () -> {
            try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl),
                    Map.of(), new Target(null, "no_such_schema"))) {
                loader.loadInput(adapterFor(Map.of("people", List.of(Map.of("id", "1")))),
                        InputStream.nullInputStream(), mapping);
            }
        });
        assertEquals(List.of(), selectPersons(), "and the unqualified table is untouched");
    }

    private List<String> personsIn(String schema) throws SQLException {
        var rows = new ArrayList<String>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select id, name from " + schema + ".person order by id")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + ":" + rs.getString(2));
            }
        }
        return rows;
    }

    /**
     * Minimal stand-in for a real adapter: hands out the canned records of the
     * requested record selector, so the loader can be tested without pulling in
     * a concrete format module.
     */
    private static InputAdapter adapterFor(Map<String, List<Map<String, String>>> recordsBySelector) {
        return (source, recordSelector, fieldSelectors) -> {
            var fields = fieldSelectors.stream()
                    .map(name -> new Field(name, String.class))
                    .toList();
            var rows = recordsBySelector.getOrDefault(recordSelector, List.of())
                    .stream()
                    .map(record -> (Row) record::get);
            return new Result(fields, rows);
        };
    }

    private List<List<String>> selectPersons() throws SQLException {
        var result = new ArrayList<List<String>>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select id, name, city from person order by id")) {
            while (rs.next()) {
                result.add(Arrays.asList(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        return result;
    }

}
