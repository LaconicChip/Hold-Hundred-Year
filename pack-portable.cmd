@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem =========================================================================
rem  打「下载解压即玩」绿色包：朋友电脑上不用装 Java / Node / MySQL。
rem      pack-portable.cmd
rem  产物：dist\hold-hundred-year-portable-<版本>.zip
rem    解压 -> 双击 hold-hundred-year.exe -> 弹独立游戏窗口；停止游戏.bat 可强制退出。
rem
rem  可选：set BUNDLE_BROWSER=1 后再跑，会往包里塞一个 Chromium（约 +90MB 压缩后）。
rem    为什么要自带：系统 Edge 开着"启动加速"时会把新窗口交给后台已有的 Edge 进程，
rem    我们启动的那个进程 0 秒就退出，"关掉窗口=退出游戏"就不可靠；自带浏览器的进程
rem    是我们自己的，关窗即退出才成立。不带也能玩，只是退出要靠 停止游戏.bat。
rem
rem  注意：便携包只在"升版本号 + 明确要求打包"两个条件都满足时才重新生成（见 AGENTS.md）。
rem  本文件与 game.cmd 一样必须存成 GBK(936)：cmd 读 UTF-8 中文批处理会按字节读错位。
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

echo [1/5] 打 jar（含前端）...
call game.cmd build || exit /b 1
if not exist "target\%JAR%" (echo  没找到 target\%JAR% & exit /b 1)

echo [2/5] 准备 jpackage 输入目录 ...
if exist "%OUT%" rmdir /s /q "%OUT%"
if exist "%IN%" rmdir /s /q "%IN%"
mkdir "%IN%"
copy /y "target\%JAR%" "%IN%\" >nul

echo [3/5] jpackage 免安装应用镜像（捆 JRE，profile=portable）...
"%JAVA_HOME%\bin\jpackage.exe" --type app-image --name %APP% ^
  --app-version %VER% --input "%IN%" --main-jar "%JAR%" ^
  --main-class org.springframework.boot.loader.launch.JarLauncher ^
  --java-options "-Dspring.profiles.active=portable" ^
  --dest "%OUT%"
if errorlevel 1 (echo  jpackage 失败 & exit /b 1)

echo [4/5] 可选：自带 Chromium ...
if /i not "%BUNDLE_BROWSER%"=="1" (
  echo      跳过（要自带：set BUNDLE_BROWSER=1 后重跑本脚本）
) else (
  call npx --yes playwright install chromium || echo      Playwright 下载失败，先用系统浏览器
  set "CHROME_DIR="
  for /d %%d in ("%USERPROFILE%\AppData\Local\ms-playwright\chromium-*") do set "CHROME_DIR=%%d"
  if not defined CHROME_DIR set "CHROME_DIR=%USERPROFILE%\AppData\Local\ms-playwright\chromium-headless-shell"
  if exist "%CHROME_DIR%\chrome-win\chrome.exe" (
    mkdir "%OUT%\%APP%\browser" 2>nul
    xcopy /e /i /q /y "%CHROME_DIR%\chrome-win\*" "%OUT%\%APP%\browser\" >nul
    echo      已自带：%OUT%\%APP%\browser\chrome.exe
  ) else (
    echo      没找到 chrome-win\chrome.exe，跳过自带
  )
)

echo [5/5] 附说明、压成 zip ...
> "%OUT%\%APP%\怎么玩.txt" (
  echo  《你持有一百年看看》 绿色版
  echo.
  echo  1. 解压到任意文件夹（路径里有中文和空格都没关系）
  echo  2. 双击 hold-hundred-year.exe
  echo  3. 会弹出一个独立游戏窗口（没有地址栏），里面点"注册"，起个名字就能玩
  echo  4. 退出：关掉那个游戏窗口；窗口关不掉或没弹出来，就双击 停止游戏.bat
  echo.
  echo  存档：同目录 data\hold_year.mv.db 这一个文件。复制它=备份，删掉它=重开一局。
  echo  同一局域网里也可以只开一份：别人用 http://你的IP:8080 进来各自注册，互不影响。
  echo  第一次运行 Windows 若提示"已保护你的电脑"：点 更多信息 -> 仍然要运行（未做代码签名）
  echo  没弹出窗口时，手动开浏览器访问 http://localhost:8080 一样能玩
)
> "%OUT%\%APP%\停止游戏.bat" (
  echo @taskkill /f /im %APP%.exe ^>nul 2^>^&1
  echo @echo 已退出游戏。若游戏窗口还开着，直接关掉它即可。
  echo @pause
)

if not exist dist mkdir dist
set "ZIP=dist\%APP%-portable-%VER%.zip"
if exist "%ZIP%" del /q "%ZIP%"
pwsh -NoProfile -Command "Compress-Archive -Force -Path '%OUT%\%APP%' -DestinationPath '%ZIP%'"
if errorlevel 1 (echo  压缩失败（需要 PowerShell 7 的 pwsh 在 PATH 里） & exit /b 1)

for %%f in ("%ZIP%") do set "ZBYTES=%%~zf"
set /a ZMB=ZBYTES/1048576
echo  完成：%ZIP%  约 !ZMB! MB
endlocal