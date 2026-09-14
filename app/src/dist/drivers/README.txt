The JDBC drivers. What is in this directory is what the server can connect to -
installing one is copying its jar in here, and removing one is deleting it.
Nothing names them in code; java.sql finds a Driver by service binding, and the
launcher puts this directory on the module path.

Only H2 is here, and the reason is the tutorial: it runs against H2, so without
this jar the documented first five minutes would need a database provisioned
before they could start. That is the whole rule - the distribution carries the
driver its own quickstart needs, and nothing else.

It is deliberately not a list of the drivers we could ship. Plenty are freely
redistributable - Microsoft's mssql-jdbc is MIT, xerial's SQLite driver is
Apache-2.0, MariaDB Connector/J is LGPL-2.1, and PostgreSQL's is BSD-2-Clause -
so redistributability was never what made the cut, whatever this file said
before 0.56. Shipping a jar buys nobody anything that copying one in does not,
it makes us the distributor of a driver we do not patch once a release is
tagged, and choosing a few implies a ranking of databases that this toolkit
does not have.

So for PostgreSQL, which shipped here until 0.55, take

    org.postgresql:postgresql

from Maven Central. For Oracle, take ojdbc17 -

    com.oracle.database.jdbc:ojdbc17

from Maven Central or from oracle.com. Any other database is the same operation
with a different jar. Then point jdbc.url at it in conf/xldr.properties and the
server will find it; if it does not, the driver is the wrong one for the URL
rather than in the wrong place.

If that is not it, or the failure did not say which of the two it was, please
open an issue: https://github.com/ralfspoeth/xldr/issues. Say which database and
version, which driver jar is in here, and what the URL scheme is. The build's
tests run against H2 and the toolkit is used against Oracle, so a report from
anything else is telling us something we had no way to find out ourselves. The
README.md beside this directory says what else is worth putting in.
