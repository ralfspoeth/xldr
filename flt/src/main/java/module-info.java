import io.github.ralfspoeth.xldr.flt.FixedLengthInputAdapterFactory;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;

/**
 * The fixed-length input adapter, addressing fields by character position.
 */
module io.github.ralfspoeth.xldr.flt {
    requires transitive io.github.ralfspoeth.xldr.ia;
    provides InputAdapterFactory
            with FixedLengthInputAdapterFactory;
}