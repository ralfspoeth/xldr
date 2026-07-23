@echo off
setlocal
set HERE=%~dp0..
java %JAVA_OPTS% -p "%HERE%\lib" -m com.pd.xldr.app/com.pd.xldr.app.Main %*
