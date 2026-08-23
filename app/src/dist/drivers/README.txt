The JDBC drivers. What is in this directory is what the server can connect to -
installing one is copying its jar in here, and removing one is deleting it.
Nothing names them in code; java.sql finds a Driver by service binding, and the
launcher puts this directory on the module path.

H2 and PostgreSQL are here, both freely redistributable. Nothing else is, and
that is a deliberate limit rather than a list that has not been kept up: a
driver we ship is a licence we have taken on, and it buys nobody anything that
copying a jar in here does not.

For Oracle, take ojdbc17 from Maven Central -

    com.oracle.database.jdbc:ojdbc17

or from oracle.com, and drop the jar in here. Any other database is the same
operation with a different jar. Then point jdbc.url at it in conf/xldr.properties
and the server will find it; if it does not, the driver is the wrong one for the
URL rather than in the wrong place.
