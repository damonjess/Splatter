@echo off
REM One-click ADB export script to transfer ONLY 3D PLY models from phone to PC
set EXPORT_DIR=%USERPROFILE%\Desktop\Splatter3D_Models
if not exist "%EXPORT_DIR%" mkdir "%EXPORT_DIR%"

echo ==========================================
echo   Splatter 3D - Export PLY Models to PC
echo ==========================================
echo Searching phone for .ply models...
adb pull /sdcard/Download/Splatter3D/ "%EXPORT_DIR%\" 2>nul
adb pull /sdcard/Android/data/com.example.splatter/files/scans/ "%EXPORT_DIR%\" 2>nul

echo.
echo Export complete! Files saved to: %EXPORT_DIR%
echo You can open these .ply files directly in MeshLab.
pause
