@echo off
setlocal
set "SAP_LIB=D:\SAP BO\SAP BO\SAP BusinessObjects Enterprise XI 4.0\java\lib"
if not exist "out\com\bocommander\BOCommander.class" (
    echo Compiled classes not found. Run build.bat first.
    exit /b 1
)
java -cp "out;%SAP_LIB%\*" com.bocommander.BOCommander
endlocal
