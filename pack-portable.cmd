@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem =========================================================================
rem  打「下载解压即玩」的绿色包：朋友电脑上不用装 Java / Node / MySQL。
rem     pack-portable.cmd
rem  产物：dist\hold-hundred-year-portable-<版本>.zip
rem  里面是一个免安装目录：双击 hold-hundred-year.exe 就开服并自动开浏览器，
rem  存档落在同目录的 data\hold_year.mv.db（一个文件，复制即备份）。
rem  实现：jar 用 portable 档（内嵌 H2 文件库），jpackage 打 app-image 顺带捆一个 JRE。
rem  注意：本文件与 game.cmd 一样必须存成 GBK(936)，改成 UTF-8 会被 cmd 按字节读错位。
rem =========================================================================

set "VER=0.0.1"
set "JAR=Hold-Hundred-Year-%VER%.jar"
set "IN=target\package-in"
set "OUT=target\package-out"
set "APP=hold-hundred-year"

if not defined JAVA_HOME (
  if exist "C:\Users\chip\.jdks\ms-17.0.20.1\bin\jpackage.exe" set "JAVA_HOME=C:\Users\chip\.jdks\ms-17.0.20.1"
)
if not exist "%JAVA_HOME%\bin\jpackage.exe" (
  echo  没找到 jpackage.exe（JDK 17 自带）。请把 JAVA_HOME 指到完整的 JDK 17 目录。
  exit /b 1
)

echo [1/4] 打 jar（含前端）...
call game.cmd build || exit /b 1
if not exist "target\%JAR%" (echo  没找到 target\%JAR% & exit /b 1)

echo [2/4] 准备 jpackage 输入目录 ...
if exist "%OUT%" rmdir /s /q "%OUT%"
if exist "%IN%" rmdir /s /q "%IN%"
mkdir "%IN%"
copy /y "target\%JAR%" "%IN%\" >nul

echo [3/4] jpackage 免安装应用镜像（捆 JRE，profile=portable）...
"%JAVA_HOME%\bin\jpackage.exe" --type app-image --name %APP% ^
  --app-version %VER% --input "%IN%" --main-jar "%JAR%" ^
  --main-class org.springframework.boot.loader.launch.JarLauncher ^
  --java-options "-Dspring.profiles.active=portable" ^
  --dest "%OUT%"
if errorlevel 1 (echo  jpackage 失败 & exit /b 1)

> "%OUT%\%APP%\怎么玩.txt" (
  echo  《你持有一百年看看》 绿色版
  echo.
  echo  1. 解压到任意文件夹（路径里有中文和空格都没关系）
  echo  2. 双击 hold-hundred-year.exe
  echo  3. 浏览器会自动打开 http://localhost:8080 ；没自动开就自己输这个地址
  echo  4. 页面里点"注册"，起个名字就能玩，不用装任何东西
  echo.
  echo  存档：同目录 data\hold_year.mv.db 这一个文件。想备份就复制它，想重开一局就删掉它。
  echo  想和朋友联机：各自解压各自玩就行；同一局域网里也可以只开一份，
  echo    别人用 http://你的IP:8080 进来各注册一个号，互不影响。
  echo  第一次启动 Windows 可能弹防火墙提示，点"允许访问"。
)

echo [4/4] 压成 zip ...
if not exist dist mkdir dist
set "ZIP=dist\%APP%-portable-%VER%.zip"
if exist "%ZIP%" del /q "%ZIP%"
pwsh -NoProfile -Command "Compress-Archive -Force -Path '%OUT%\%APP%' -DestinationPath '%ZIP%'"
if errorlevel 1 (echo  压缩失败（需要 PowerShell 7 的 pwsh 在 PATH 里） & exit /b 1)

for %%f in ("%ZIP%") do set "ZBYTES=%%~zf"
set /a ZMB=ZBYTES/1048576
echo  完成：%ZIP%  约 !ZMB! MB（zip 里已含 JRE 与前端，朋友电脑零依赖）
echo  把这个 zip 发给朋友就行。
endlocal