import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.json.JsonInputAdapterFactory;

module io.github.ralfspoeth.xldr.json {
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires io.github.ralfspoeth.greyson;
    provides InputAdapterFactory
            with JsonInputAdapterFactory;
}