package com.pd.xldr.ldr;

import com.pd.xldr.spec.MappingSpec;
import com.pd.xldr.spec.OutputSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ResourceBundle;


public class LoaderTest {

    private Connection dz_t2;
    private OutputSpec os;
    private MappingSpec ms;


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
        }*/
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

             */
        }
    }


    @BeforeEach
    public void prepareConn() throws SQLException {
        var jdbcUrl = ResourceBundle.getBundle("dz_t2").getString("url");
        os = new OutputSpec(jdbcUrl, System.getProperties());
        ms = new MappingSpec(null, List.of(), os);
    }

}
