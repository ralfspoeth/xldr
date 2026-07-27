package io.github.ralfspoeth.xldr.ldr.test;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.ldr.Loader;
import io.github.ralfspoeth.xldr.spec.ValueSource;
import io.github.ralfspoeth.xldr.spec.FieldMappingSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.RecordMappingSpec;
import io.github.ralfspoeth.xldr.spec.VarSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class LoaderTest {

    private String jdbcUrl;

/*
    @Test
    public void testInsKurs() throws SQLException {
        String lfnr = "test1-" + LocalDateTime.now();
        /*
        try (var ldr = new Loader(ms)) {
            ldr.prepareInsert("snlieferung", List.of("lieferung_nr", "schnittstelle_cd", "institut_nr", "neu_dat", "syssnliefart_cd", "syssnliefstatus_cd"));
            ldr.insert("snlieferung", lfnr, "PD", "1", new Date(System.currentTimeMillis()), "IMP", "WAIT");
            ldr.prepareInsert("snkurs", List.of("kurs_dat", "syssnmut_cd", "lieferung_nr"));
            ldr.insert("snkurs", Date.valueOf(LocalDate.now()), "X", lfnr);
            ldr.insert("snkurs", Date.valueOf(LocalDate.now()), "UEX", lfnr);
        }
    }

    @Test
    public void testDefaults() throws SQLException {
        if (this.ms == null) return;
        try (var ldr = new Loader(ms)) {
            System.out.println(ldr.defaultInstitut());
            System.out.println(ldr.defaultSnDef());
            System.out.println(ldr.defaultJobDef());
        }
    }

    @Test
    public void testInsKurs2AndTrigger() throws SQLException {
        String lfnr = "test2-" + LocalDateTime.now();
        if (this.ms == null) return;
        try (var ldr = new Loader(ms)) {
            ldr.generateImportHeader(lfnr, true);
            /*
            ldr.prepareInsert("snkurs", List.of("lieferung_nr", "syssnmut_cd", "kurs_dat", "valident_txt", "kurs", "waehrung_cd"));
            ldr.insert("snkurs", lfnr, "X", Date.valueOf(LocalDate.now()), "519000", new BigDecimal("1000"), "EUR");
            ldr.insert("snkurs", lfnr, "X", Date.valueOf(LocalDate.now().minusDays(1)), "519000", new BigDecimal("1003"), "EUR");
            ldr.insert("snkurs", lfnr, "X", Date.valueOf(LocalDate.now().minusDays(2)), "519000", new BigDecimal("998"), "EUR");
            ldr.insert("snkurs", lfnr, "X", Date.valueOf(LocalDate.now().minusDays(3)), "519000", new BigDecimal("995"), "EUR");
            ldr.insert("snkurs", lfnr, "X", Date.valueOf(LocalDate.now().minusDays(4)), "519000", new BigDecimal("996"), "EUR");
            ldr.triggerImport(lfnr);


        }
    }
*/

    @BeforeEach
    public void prepareConn() throws SQLException {
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
    public void loadsSeveralMappingsIntoTheSameTable() throws Exception {
        // column order deliberately differs from the natural one: name before id
        var people = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec(new ValueSource.Field("name"), "name"),
                new FieldMappingSpec(new ValueSource.Field("id"), "id")
        ));
        // same table, spelled in upper case, and a different set of columns
        var visitors = new RecordMappingSpec("visitors", "PERSON", List.of(
                new FieldMappingSpec(new ValueSource.Field("id"), "id"),
                new FieldMappingSpec(new ValueSource.Field("city"), "city")
        ));
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of()),
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
        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
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

    /**
     * More records than fit in one batch: every row still arrives, exactly once,
     * and the reported count is the number of rows rather than the number of
     * batches. The count deliberately straddles a batch boundary so that a
     * dropped or double-sent final partial batch would show.
     */
    @Test
    public void loadsMoreRecordsThanOneBatch() throws Exception {
        var mapping = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec(new ValueSource.Field("id"), "id"),
                new FieldMappingSpec(new ValueSource.Field("name"), "name")
        ));
        var spec = new MappingSpec(new InputSpec("text/csv", List.of()), List.of(mapping));

        int records = 2_500;
        var people = new ArrayList<Map<String, String>>(records);
        for (int i = 0; i < records; i++) {
            people.add(Map.of("id", String.valueOf(i), "name", "p" + i));
        }
        var adapter = adapterFor(Map.of("people", people));

        int inserted;
        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
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
    public void reusesTheStatementOfTwoIdenticalMappings() throws Exception {
        var columns = List.of(
                new FieldMappingSpec(new ValueSource.Field("id"), "id"),
                new FieldMappingSpec(new ValueSource.Field("name"), "name"));
        var first = new RecordMappingSpec("people", "person", columns);
        var second = new RecordMappingSpec("visitors", "person", columns);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of()), List.of(first, second));

        var adapter = adapterFor(Map.of(
                "people", List.of(Map.of("id", "1", "name", "Alice")),
                "visitors", List.of(Map.of("id", "2", "name", "Bob"))));

        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
            assertEquals(1, loader.loadInput(adapter, InputStream.nullInputStream(), first));
            assertEquals(1, loader.loadInput(adapter, InputStream.nullInputStream(), second));
        }

        assertEquals(
                List.of(Arrays.asList("1", "Alice", null), Arrays.asList("2", "Bob", null)),
                selectPersons());
    }

    /**
     * A failing mapping has to take the whole load down with it - close() rolls
     * back instead of committing.
     */
    @Test
    public void rollsBackWhenAMappingFails() throws Exception {
        var good = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec(new ValueSource.Field("id"), "id")
        ));
        var broken = new RecordMappingSpec("people", "no_such_table", List.of(
                new FieldMappingSpec(new ValueSource.Field("id"), "id")
        ));
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of()),
                List.of(good, broken)
        );
        var adapter = adapterFor(Map.of("people", List.of(Map.of("id", "1"))));

        assertThrows(SQLException.class, () -> {
            try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
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
    public void rejectsForeignMapping() throws Exception {
        var known = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec(new ValueSource.Field("id"), "id")
        ));
        var foreign = new RecordMappingSpec("elsewhere", "person", List.of(
                new FieldMappingSpec(new ValueSource.Field("id"), "id")
        ));
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of()),
                List.of(known)
        );
        var adapter = adapterFor(Map.of());

        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
            assertThrows(IllegalArgumentException.class,
                    () -> loader.loadInput(adapter, InputStream.nullInputStream(), foreign));
        }
    }

    /**
     * A field value and a spec constant share one insert: the field is bound from
     * the row, the constant from the spec, and every row carries it.
     */
    @Test
    public void insertsFieldsAndConstants() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists event");
            stmt.execute("create table event(id varchar(10), source varchar(10))");
        }
        var mapping = new RecordMappingSpec("events", "event", List.of(
                new FieldMappingSpec(new ValueSource.Field("id"), "id"),
                new FieldMappingSpec(new ValueSource.Constant("PD"), "source")
        ));
        var spec = new MappingSpec(new InputSpec("text/csv", List.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("events", List.of(Map.of("id", "1"), Map.of("id", "2"))));

        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
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
    public void bindsALookupVarToEveryRow() throws Exception {
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
                new FieldMappingSpec(new ValueSource.Field("id"), "id"),
                new FieldMappingSpec(new ValueSource.Var("bid"), "batch_id")
        ));
        var spec = new MappingSpec(
                new InputSpec("text/csv", null, null, List.of(), vars),
                List.of(mapping));
        var adapter = adapterFor(Map.of("rows", List.of(
                Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3"))));

        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
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
    public void expressionGeneratesAnIdOncePerLoad() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists doc");
            stmt.execute("create table doc(id varchar(10), gen varchar(40))");
        }
        var vars = List.of(new VarSpec("gid",
                new ValueSource.Expr("${xldr.filename}-${nextval('batch')}")));
        var mapping = new RecordMappingSpec("rows", "doc", List.of(
                new FieldMappingSpec(new ValueSource.Field("id"), "id"),
                new FieldMappingSpec(new ValueSource.Var("gid"), "gen")
        ));
        var spec = new MappingSpec(
                new InputSpec("text/csv", null, null, List.of(), vars),
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
    public void expressionNumbersRowsWithNextval() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists seq_rows");
            stmt.execute("create table seq_rows(id varchar(10), n integer)");
        }
        var mapping = new RecordMappingSpec("rows", "seq_rows", List.of(
                new FieldMappingSpec(new ValueSource.Field("id"), "id"),
                new FieldMappingSpec(new ValueSource.Expr("${nextval('r', 10)}"), "n")
        ));
        var spec = new MappingSpec(new InputSpec("text/csv", List.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("rows", List.of(
                Map.of("id", "a"), Map.of("id", "b"), Map.of("id", "c"))));

        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
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
     * The optional increment steps the sequence by more than one, while the
     * start still governs the first draw.
     */
    @Test
    public void expressionSequenceHonoursStartAndIncrement() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists inc_rows");
            stmt.execute("create table inc_rows(id varchar(10), n integer)");
        }
        var mapping = new RecordMappingSpec("rows", "inc_rows", List.of(
                new FieldMappingSpec(new ValueSource.Field("id"), "id"),
                new FieldMappingSpec(new ValueSource.Expr("${nextval('r', 100, 5)}"), "n")
        ));
        var spec = new MappingSpec(new InputSpec("text/csv", List.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("rows", List.of(
                Map.of("id", "a"), Map.of("id", "b"), Map.of("id", "c"), Map.of("id", "d"))));

        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
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
     * A lookup resolves a column from a reference table via an inline subquery,
     * keyed here by an input field. A key that matches no row yields SQL NULL.
     */
    @Test
    public void resolvesLookups() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists country");
            stmt.execute("drop table if exists holder");
            stmt.execute("create table country(iso varchar(2), id int)");
            stmt.execute("insert into country values ('DE', 49), ('US', 1)");
            stmt.execute("create table holder(name varchar(20), country_id int)");
        }
        var mapping = new RecordMappingSpec("holders", "holder", List.of(
                new FieldMappingSpec(new ValueSource.Field("name"), "name"),
                new FieldMappingSpec(
                        new ValueSource.Lookup("country", "id", "iso", new ValueSource.Field("c")),
                        "country_id")
        ));
        var spec = new MappingSpec(new InputSpec("text/csv", List.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("holders", List.of(
                Map.of("name", "Alice", "c", "DE"),
                Map.of("name", "Bob", "c", "US"),
                Map.of("name", "Carol", "c", "ZZ")   // no such country -> null
        )));

        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
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
    public void honoursTheRowLimit() throws Exception {
        var mapping = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec(new ValueSource.Field("id"), "id"),
                new FieldMappingSpec(new ValueSource.Field("name"), "name")
        ), 2);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of()), List.of(mapping));
        var adapter = adapterFor(Map.of("people", List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob"),
                Map.of("id", "3", "name", "Carol"),
                Map.of("id", "4", "name", "Dave")
        )));

        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
            assertEquals(2, loader.loadInput(adapter, InputStream.nullInputStream(), mapping));
        }
        assertEquals(List.of("1:Alice", "2:Bob"),
                selectPersons().stream().map(r -> r.get(0) + ":" + r.get(1)).toList());
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
