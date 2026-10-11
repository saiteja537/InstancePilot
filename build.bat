@echo off
setlocal
set "SAP_LIB=D:\SAP BO\SAP BO\SAP BusinessObjects Enterprise XI 4.0\java\lib"
set "OUT=out"
set "SRC=src"

if not exist "%OUT%" mkdir "%OUT%"
echo Compiling InstancePilot...
javac -cp "%SAP_LIB%\*;%OUT%" -d "%OUT%" ^
    "%SRC%\com\bocommander\FounderBanner.java" ^
    "%SRC%\com\bocommander\BOCommander.java"

if errorlevel 1 (
    echo.
    echo BUILD FAILED.
    echo Check that a JDK is installed and SAP_LIB points to your SAP BO Java lib folder.
    exit /b 1
)
echo.
echo Build OK. Classes in: %OUT%\
endlocal
