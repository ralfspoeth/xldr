import io.github.ralfspoeth.xldr.JsonInputAdapterFactory;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;

module io.github.ralfspoeth.xldr.json {
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires io.github.ralfspoeth.greyson;
    provides InputAdapterFactory
            with JsonInputAdapterFactory;
}