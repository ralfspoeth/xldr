import org.jspecify.annotations.NullMarked;

/**
 * The loader: inserts the records of one input into the target database, the
 * whole input being one transaction.
 */
@NullMarked
module io.github.ralfspoeth.xldr.ldr {
    exports io.github.ralfspoeth.xldr.ldr;
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires transitive java.sql;
    requires static org.jspecify;
}
