@echo off
rem xldr server launcher.
rem
rem The server reads xldr.properties from the working directory, or from the one
rem named by --dir:
rem   cd C:\xldr\feeds && C:\xldr\bin\xldr.cmd
rem   C:\xldr\bin\xldr.cmd --dir C:\xldr\feeds
rem
rem lib\ holds the application and the input adapters, drivers\ the JDBC
rem drivers, and xl\ the Excel adapter with the Apache POI libraries it needs.
rem All three go on the module path, where JPMS service binding finds the
rem adapters and the driver. Put your own driver jar in drivers\ and remove the
rem ones you do not target; delete xl\ whole if you read no spreadsheets.
rem
rem java is taken from JAVA_HOME when that is set, and from PATH otherwise.
rem JAVA_OPTS may carry extra VM options.
setlocal
set "HERE=%~dp0.."

if not exist "%HERE%\lib" (
    echo xldr: no lib\ in %HERE% - is the distribution complete? 1>&2
    exit /b 1
)

if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA=java.exe"
)
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" (
    echo xldr: JAVA_HOME is set to %JAVA_HOME%, but %JAVA_HOME%\bin\java.exe does not exist 1>&2
    exit /b 1
)

rem drivers\ and xl\ may be absent, or empty, and both are fine
set "MODULES=%HERE%\lib"
if exist "%HERE%\drivers" set "MODULES=%MODULES%;%HERE%\drivers"
if exist "%HERE%\xl" set "MODULES=%MODULES%;%HERE%\xl"

"%JAVA%" %JAVA_OPTS% -Dxldr.home="%HERE%" -p "%MODULES%" ^
    -m io.github.ralfspoeth.xldr.app/io.github.ralfspoeth.xldr.app.App %*
