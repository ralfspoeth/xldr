import org.jspecify.annotations.NullMarked;

@NullMarked
module io.github.ralfspoeth.xldr.it {
    requires transitive io.github.ralfspoeth.xldr.server;
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires transitive io.github.ralfspoeth.xldr.ldr;
    requires transitive java.management;
    requires transitive java.sql;
    requires static org.jspecify;
}