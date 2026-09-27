# quarkus-sample

The same webapp again, under Quarkus, as an executable jar rather than a war:

    cd samples/quarkus-sample && mvn quarkus:dev

then, from another shell,

    curl -i --data-binary @samples/quarkus-sample/src/test/resources/customers.csv \
         -H 'Content-Type: text/csv' \
         'http://localhost:8080/load?spec=customers'

`cd` first, and that matters here in a way it does not for the other two - see below.

## Why this one needed a change to xlet

The war samples read their specs from `/WEB-INF/specs/` through the
`ServletContext`. A Quarkus application has no `/WEB-INF/`: it is a jar, and
`getResourcePaths` has nothing to answer with. Until the `specs` parameter existed
this deployment could not work at all - the servlet came up carrying no specs and
answered `404` to everything, which looks like a missing spec rather than a wrong
shape of deployment.

So the specs here live in `specs/` **beside the project rather than inside the
artifact**, and `@WebInitParam(name = "specs", value = "specs")` says so.

**That path is relative, which is right for a sample and wrong for a deployment.**
It resolves against the working directory, which is why you have to `cd` - the same
trap `xldr.roots` had until 1.2.0, except that this one is the deployer's to resolve
and not ours. Name an absolute path in anything real.

**And a directory is not protected the way `/WEB-INF/` was.** Table and column names
from a spec reach the SQL unbound, so installing a spec is as privileged as editing
the application's configuration: whatever can write to that directory decides what
this application runs. Put it where the configuration lives and own it as tightly.
That is the trade this deployment shape makes, and it is the deployer's to make.

## What it shares with the others

**The `DataSource` seam.** Quarkus has no JNDI either, so `dataSource()` is overridden
exactly as in the Spring sample - four lines, `@Inject` where Spring had a
constructor. One `protected` method covers three containers that resolve a database
three different ways.

**The spec.** Byte-for-byte the one in `jetty-sample` and `spring-sample`, and it
would work unchanged as a feed's `spec.json` under the file server. Across four
deployments the mapping is the same document; what differs is where the input arrives
from, where the database comes from, and now where the specs are read.

**No `security-constraint`.** As in the others, so `curl` works. Under Quarkus that is
`quarkus-security` rather than `web.xml`. Anyone who can POST here can write to your
tables.

## What is where

    pom.xml                                    jar packaging, xlet, csv, quarkus-undertow
    src/main/java/.../QuarkusLoaderServlet.java  @WebServlet, the DataSource, the specs param
    src/main/resources/application.properties  the datasource Quarkus builds
    specs/customers.json                       outside the artifact, on purpose
    src/test/resources/customers.csv           something to POST

`quarkus-undertow` is what makes a `jakarta.servlet.http.HttpServlet` deployable here
at all; Quarkus's own world is Quarkus REST and does not include Servlet support by
default. The adapters are found through the `META-INF/services` each carries, this
being a classpath rather than a module path.
