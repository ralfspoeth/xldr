# spring-sample

The same webapp as [`jetty-sample`](../jetty-sample), under Spring Boot:

    mvn -pl samples/spring-sample spring-boot:run

then, from another shell,

    curl -i --data-binary @samples/spring-sample/src/test/resources/customers.csv \
         -H 'Content-Type: text/csv' \
         'http://localhost:8080/load?spec=customers'

## What it shows that the Jetty one does not

**A `DataSource` that did not come from JNDI.** `XldrServlet.dataSource()` is
`protected` for exactly this, and its javadoc says so: *"a deployment that has its
DataSource from Spring, from a CDI producer or from anywhere else overrides this and
never touches a directory."* `SpringDataSourceServlet` is that override, and it is
four lines. Without it the application would have to switch on JNDI inside an
embedded Tomcat to satisfy a lookup it has no use for.

**Registration in Java rather than in `web.xml`.** A `ServletRegistrationBean` carries
the mapping, the init-params and `load-on-startup`, so there is no `web.xml` here at
all. The servlet does not know the difference.

The spec is byte-for-byte the one in `jetty-sample`, and would work unchanged under
the file server as a feed's `spec.json`. That is the thing worth noticing: what
changes between these three deployments is where the input arrives from and where the
database comes from, and never the mapping.

## What it still has to obey

**`war` packaging** - by choice here, not by necessity. Specs come from
`/WEB-INF/specs/` through the `ServletContext`, which an executable jar has no
equivalent of; such a deployment sets the `specs` parameter to a directory instead,
which is what [`quarkus-sample`](../quarkus-sample) shows. A Spring Boot war runs from
one command anyway and also deploys to a standalone container, so this sample takes
the simpler of the two roads and leaves the other where it cannot be avoided.

**A `security-constraint`, which this sample omits.** As in the Jetty one, and for the
same reason: so that a plain `curl` works. Anyone who can POST here can write to your
tables. Under Spring that block is Spring Security rather than `web.xml`, which is
part of why you would choose this deployment in the first place.

## What is where

    pom.xml                                        war packaging, xlet, csv, Spring Boot
    src/main/java/.../LoaderApplication.java       the registration and the DataSource override
    src/main/resources/application.properties      the database Spring builds
    src/main/resources/schema.sql                  the table the spec loads into
    src/main/webapp/WEB-INF/specs/customers.json   one spec; ?spec=customers names it
    src/test/resources/customers.csv               something to POST
