@echo off
setlocal enabledelayedexpansion

REM Script simplificado para ejecutar pruebas de carga baseline para API Gateway AppIgle
REM Requiere k6 instalado: https://k6.io/docs/getting-started/installation/

REM Configuración
set "API_URL=https://appigle-gateway.politedune-48459ced.eastus.azurecontainerapps.io"
set "TEST_SCRIPT=.\gateway-baseline.js"
set "RESULTS_DIR=.\results"
set "TIMESTAMP=%date:~-4,4%%date:~-7,2%%date:~-10,2%_%time:~0,2%%time:~3,2%%time:~6,2%"
set "TIMESTAMP=!TIMESTAMP: =0!"

REM Verificar que k6 está instalado
k6 version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: k6 no está instalado. Por favor, instálalo desde https://k6.io/docs/getting-started/installation/
    exit /b 1
)

REM Verificar que existe el directorio de resultados
if not exist "%RESULTS_DIR%" mkdir "%RESULTS_DIR%"

echo.
echo =========================================================
echo  INICIANDO PRUEBA BASELINE PARA APPIGLE API GATEWAY
echo  URL: %API_URL%
echo  Timestamp: %TIMESTAMP%
echo =========================================================
echo.

REM Ejecutar prueba
k6 run ^
  --env API_URL=%API_URL% ^
  --out json=%RESULTS_DIR%\baseline_%TIMESTAMP%.json ^
  --out csv=%RESULTS_DIR%\baseline_%TIMESTAMP%.csv ^
  --summary-export=%RESULTS_DIR%\baseline_%TIMESTAMP%_summary.json ^
  %TEST_SCRIPT%

echo.
echo =========================================================
echo  PRUEBA COMPLETADA
echo  Resultados guardados en:
echo  - %RESULTS_DIR%\baseline_%TIMESTAMP%.csv
echo  - %RESULTS_DIR%\baseline_%TIMESTAMP%.json
echo  - %RESULTS_DIR%\baseline_%TIMESTAMP%_summary.json
echo =========================================================

REM Crear archivo HTML para análisis posterior
echo ^<!DOCTYPE html^> > "%RESULTS_DIR%\baseline_%TIMESTAMP%_report.html"
echo ^<html^> >> "%RESULTS_DIR%\baseline_%TIMESTAMP%_report.html"
echo ^<head^> >> "%RESULTS_DIR%\baseline_%TIMESTAMP%_report.html"
echo     ^<title^>Reporte de Prueba Baseline %TIMESTAMP%^</title^> >> "%RESULTS_DIR%\baseline_%TIMESTAMP%_report.html"
echo     ^<meta http-equiv="refresh" content="0; url='../dashboard.html?file=baseline_%TIMESTAMP%.csv'" /^> >> "%RESULTS_DIR%\baseline_%TIMESTAMP%_report.html"
echo ^</head^> >> "%RESULTS_DIR%\baseline_%TIMESTAMP%_report.html"
echo ^<body^> >> "%RESULTS_DIR%\baseline_%TIMESTAMP%_report.html"
echo     ^<p^>Redirigiendo al dashboard...^</p^> >> "%RESULTS_DIR%\baseline_%TIMESTAMP%_report.html"
echo ^</body^> >> "%RESULTS_DIR%\baseline_%TIMESTAMP%_report.html"
echo ^</html^> >> "%RESULTS_DIR%\baseline_%TIMESTAMP%_report.html"

echo.
echo Para ver el reporte completo, abre: %RESULTS_DIR%\baseline_%TIMESTAMP%_report.html
echo.

exit /b 0