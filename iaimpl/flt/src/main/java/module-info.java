import io.github.ralfspoeth.xldr.flt.FixedLengthInputAdapterFactory;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import org.jspecify.annotations.NullMarked;

/**
 * The fixed-length input adapter, addressing fields by character position.
 */
@NullMarked
module io.github.ralfspoeth.xldr.flt {
    requires io.github.ralfspoeth.xldr.ia;
    requires static org.jspecify;
    provides InputAdapterFactory
            with FixedLengthInputAdapterFactory;
}