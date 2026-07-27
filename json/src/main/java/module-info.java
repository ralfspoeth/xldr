import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.json.JsonInputAdapterFactory;

/**
 * The JSON input adapter, selecting records and fields with Greyson pointers.
 */
module io.github.ralfspoeth.xldr.json {
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires io.github.ralfspoeth.greyson;
    provides InputAdapterFactory
            with JsonInputAdapterFactory;
}