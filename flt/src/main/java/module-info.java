import io.github.ralfspoeth.xldr.flt.FixedLengthInputAdapterFactory;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;

module io.github.ralfspoeth.xldr.flt {
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires io.github.ralfspoeth.greyson;
    provides InputAdapterFactory
            with FixedLengthInputAdapterFactory;
}