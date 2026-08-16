/**
 * An xldr front end for a servlet container: one input per request, loaded through
 * a spec the deployment carries.
 * <p>
 * Nothing here is discovered as a service and nothing is provided as one. The
 * adapters are, but that lookup happens inside {@code ia} - so a deployment names
 * its adapters by putting them on the module path and this module needs no
 * {@code uses} of its own.
 */
module io.github.ralfspoeth.xldr.xlet {

    // supplied by the container, hence provided scope in the pom
    requires jakarta.servlet;

    // ldr brings ia, which brings spec, and java.sql with it
    requires io.github.ralfspoeth.xldr.ia;
    requires io.github.ralfspoeth.xldr.ldr;

    // the DataSource comes from the container's directory
    requires java.naming;

    exports io.github.ralfspoeth.xldr.xlet;

    uses io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
}
