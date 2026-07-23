package com.pd.xldr.ldr.test;

import com.pd.xldr.ia.Field;
import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.Result;
import com.pd.xldr.ia.Row;
import com.pd.xldr.ldr.Loader;
import com.pd.xldr.spec.ColumnSource;
import com.pd.xldr.spec.FieldMappingSpec;
import com.pd.xldr.spec.InputSpec;
import com.pd.xldr.spec.MappingSpec;
import com.pd.xldr.spec.LoadSpec;
import com.pd.xldr.spec.RecordMappingSpec;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


public class LoaderTest {

    private LoadSpec loadSpec;
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
        // the loader is handed a connection; the load spec only says when to
        // commit, which defaults to ON_CLOSE
        loadSpec = new LoadSpec();
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
                new FieldMappingSpec("name", "name"),
                new FieldMappingSpec("id", "id")
        ));
        // same table, spelled in upper case, and a different set of columns
        var visitors = new RecordMappingSpec("visitors", "PERSON", List.of(
                new FieldMappingSpec("id", "id"),
                new FieldMappingSpec("city", "city")
        ));
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of()),
                List.of(people, visitors),
                loadSpec
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
     * A failing mapping has to take the whole load down with it - close() rolls
     * back instead of committing.
     */
    @Test
    public void rollsBackWhenAMappingFails() throws Exception {
        var good = new RecordMappingSpec("people", "person", List.of(
                new FieldMappingSpec("id", "id")
        ));
        var broken = new RecordMappingSpec("people", "no_such_table", List.of(
                new FieldMappingSpec("id", "id")
        ));
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of()),
                List.of(good, broken),
                loadSpec
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
                new FieldMappingSpec("id", "id")
        ));
        var foreign = new RecordMappingSpec("elsewhere", "person", List.of(
                new FieldMappingSpec("id", "id")
        ));
        var spec = new MappingSpec(
                new InputSpec("text/csv", List.of()),
                List.of(known),
                loadSpec
        );
        var adapter = adapterFor(Map.of());

        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
            assertThrows(IllegalArgumentException.class,
                    () -> loader.loadInput(adapter, InputStream.nullInputStream(), foreign));
        }
    }

    /**
     * A field value, a spec constant and a database function share one insert:
     * the function is emitted inline in the values list (not bound), the constant
     * and the field are bound.
     */
    @Test
    public void insertsConstantsAndFunctions() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists event");
            stmt.execute("create table event(id varchar(10), source varchar(10), loaded_at timestamp)");
        }
        var mapping = new RecordMappingSpec("events", "event", List.of(
                new FieldMappingSpec("id", "id"),
                new FieldMappingSpec(new ColumnSource.Constant("PD"), "source"),
                new FieldMappingSpec(new ColumnSource.Function("current_timestamp"), "loaded_at")
        ));
        var spec = new MappingSpec(new InputSpec("text/csv", List.of()), List.of(mapping), loadSpec);
        var adapter = adapterFor(Map.of("events", List.of(Map.of("id", "1"), Map.of("id", "2"))));

        try (var loader = new Loader(spec, DriverManager.getConnection(jdbcUrl))) {
            assertEquals(2, loader.loadInput(adapter, InputStream.nullInputStream(), mapping));
        }

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select id, source, loaded_at from event order by id")) {
            var rows = new ArrayList<List<Object>>();
            while (rs.next()) {
                rows.add(Arrays.asList(rs.getString(1), rs.getString(2), rs.getObject(3)));
            }
            assertEquals(2, rows.size());
            assertAll(
                    () -> assertEquals("1", rows.get(0).get(0)),
                    () -> assertEquals("PD", rows.get(0).get(1)),
                    // the function ran on the database, so the column is populated
                    () -> assertNotNull(rows.get(0).get(2)),
                    () -> assertEquals("PD", rows.get(1).get(1))
            );
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
                new FieldMappingSpec("name", "name"),
                new FieldMappingSpec(
                        new ColumnSource.Lookup("country", "id", "iso", new ColumnSource.Field("c")),
                        "country_id")
        ));
        var spec = new MappingSpec(new InputSpec("text/csv", List.of()), List.of(mapping), loadSpec);
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
                new FieldMappingSpec("id", "id"),
                new FieldMappingSpec("name", "name")
        ), 2);
        var spec = new MappingSpec(new InputSpec("text/csv", List.of()), List.of(mapping), loadSpec);
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
