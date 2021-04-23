package com.pd.xldr.ldr;

import com.pd.xldr.spec.MappingSpec;
import com.pd.xldr.spec.OutputSpec;

import java.sql.Date;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Loader implements AutoCloseable {
    private static final Pattern QS_PATTERN = Pattern.compile("\".*\"");
    private final Connection connection;
    private final Map<String, PreparedStatement> statementCache = new HashMap<>();


    public Loader(MappingSpec ms) throws SQLException {
        var futConn = Executors.newFixedThreadPool(1).submit(() -> openDatabaseConnection(ms.outputSpec()));
        ms.recordMappingSpecs().stream().forEach(rm -> {
            var tableName = rm.databaseTable();
            var columnList = rm.fieldMappings()
                    .stream()
                    .map(fm -> fm.databaseColumnName())
                    .filter(obj -> obj != null)
                    .distinct().collect(Collectors.toList());
        });
        try {
            this.connection = futConn.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String generateInsertStatement(String table, List<String> columns) {
        var columnList = columns.stream().collect(Collectors.joining(", "));
        var questionmarks = IntStream.range(0, columns.size())
                .mapToObj(i -> "?")
                .collect(Collectors.joining(", "));
        return String.format("insert into %s(%s) values(%s)", table, columnList, questionmarks);
    }

    public PreparedStatement prepareInsert(String statement) {
        return statementCache.computeIfAbsent(statement, (s) -> create(connection, s));
    }

    private static PreparedStatement create(Connection conn, String stmt) {
        try {
            return conn.prepareStatement(stmt);
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

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
    }

    public void insert(String table, List<String> cols, List<?> values) throws SQLException {
        var stmt = generateInsertStatement(table, cols);
        var ps = statementCache.computeIfAbsent(stmt, this::prepareInsert);
        ps.clearParameters();
        for (int i = 0; i < values.size(); i++) {
            ps.setObject(i + 1, values.get(i));
        }
        int rows = ps.executeUpdate();
        assert rows == 1;
    }


    @Override
    public void close() throws SQLException {
        try {
            connection.commit();
        } finally {
            connection.close();
        }
    }


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
    }

    private String normalizeName(String name) {
        return QS_PATTERN.matcher(name).matches() ? name : name.toUpperCase();
    }


    private static Connection openDatabaseConnection(OutputSpec spec) throws SQLException {
        Connection tmp = null;
        for (var drv : ServiceLoader.load(Driver.class)) {
            if (drv.acceptsURL(spec.url())) {
                tmp = drv.connect(spec.url(), spec.info());
                tmp.setAutoCommit(false);
                break;
            }
        }
        if (tmp == null) {
            throw new IllegalStateException("couldn't estable the target connection");
        }
        return tmp;
    }

}
