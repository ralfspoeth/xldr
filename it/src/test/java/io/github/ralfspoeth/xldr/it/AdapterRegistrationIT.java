package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ServiceLoader;
import java.util.TreeSet;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * That an adapter announces itself the same way twice: once in its
 * {@code module-info}, for the module path, and once in
 * {@code META-INF/services}, for a classpath.
 *
 * <h2>Why both, and why this has to be checked</h2>
 * {@link ServiceLoader} reads a {@code provides} directive only from a
 * <em>resolved named module</em>. A jar carrying {@code module-info} on a
 * classpath is in the unnamed module, where the directive is ignored entirely
 * and only {@code META-INF/services} is consulted. So an adapter that declares
 * itself only in its descriptor works under {@code bin/xldr}, which launches
 * with {@code -p}, and is invisible in a war, in Quarkus, in Spring Boot, or
 * anywhere else that puts jars on a classpath - with no error beyond "no adapter
 * reads that mimeType" for a spec that is perfectly correct.
 * <p>
 * Only one of the two declarations is checked by the compiler. Rename a factory
 * and {@code module-info} stops compiling; the service file goes on naming a
 * class that no longer exists, and nothing says so until someone deploys to a
 * container. That asymmetry is what this test exists for.
 *
 * <h2>Why it can read those files at all</h2>
 * Resource encapsulation applies to resources in packages. {@code META-INF} is
 * not a valid package name, so {@code META-INF/services/...} is readable through
 * the class loader even from a module that was never opened - which is also why
 * {@code ServiceLoader} can find these files in the first place.
 */
class AdapterRegistrationIT {

    private static final String SERVICE =
            "META-INF/services/" + InputAdapterFactory.class.getName();

    /**
     * The two registries agree.
     * <p>
     * What the module path resolves is the set {@code bin/xldr} offers; what the
     * service files name is the set a classpath deployment would. A difference
     * either way is a deployment that silently reads fewer formats than the one
     * it was tested on.
     */
    @Test
    void everyAdapterIsRegisteredBothWays() throws IOException {
        var onTheModulePath = new TreeSet<String>();
        ServiceLoader.load(InputAdapterFactory.class)
                .forEach(f -> onTheModulePath.add(f.getClass().getName()));
        assertFalse(onTheModulePath.isEmpty(),
                "no adapter resolved at all; this test cannot say anything");

        var inServiceFiles = declaredInServiceFiles();
        assertFalse(inServiceFiles.isEmpty(), () ->
                "no " + SERVICE + " found on the class path. Either no adapter ships one - in "
                        + "which case a war deployment reads nothing - or this test cannot see "
                        + "them, which would make it worthless rather than passing");

        assertEquals(onTheModulePath, inServiceFiles,
                "the module path and the service files offer different adapters");
    }

    /**
     * And each named class is really there and really a factory, which is the
     * half a rename breaks: {@code module-info} would stop compiling, a service
     * file naming the old name would not.
     */
    @Test
    void everyNamedClassExistsAndIsAfactory() throws IOException {
        for (var name : declaredInServiceFiles()) {
            var type = assertDoesNotThrow(() -> Class.forName(name),
                    () -> SERVICE + " names " + name + ", which is not on the path - a factory "
                            + "renamed in module-info and not here");
            assertTrue(InputAdapterFactory.class.isAssignableFrom(type),
                    () -> name + " is not an " + InputAdapterFactory.class.getSimpleName());
        }
    }

    /** every provider named by every service file the class loader can see */
    private static TreeSet<String> declaredInServiceFiles() throws IOException {
        var named = new TreeSet<String>();
        var resources = AdapterRegistrationIT.class.getClassLoader().getResources(SERVICE);
        while (resources.hasMoreElements()) {
            try (var in = resources.nextElement().openStream();
                 var reader = new BufferedReader(new InputStreamReader(in, UTF_8))) {
                reader.lines()
                        // the file format: one class per line, # comments, blanks ignored
                        .map(line -> line.replaceAll("#.*", "").strip())
                        .filter(line -> !line.isEmpty())
                        .forEach(named::add);
            }
        }
        return named;
    }
}
