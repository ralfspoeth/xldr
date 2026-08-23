import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.json.JsonInputAdapterFactory;
import org.jspecify.annotations.NullMarked;

/**
 * The JSON input adapter, selecting records and fields with Greyson pointers.
 */
@NullMarked
module io.github.ralfspoeth.xldr.json {
    requires io.github.ralfspoeth.xldr.ia;
    requires io.github.ralfspoeth.greyson;
    requires static org.jspecify;
    provides InputAdapterFactory
            with JsonInputAdapterFactory;
}