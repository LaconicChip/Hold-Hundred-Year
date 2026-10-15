@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem =========================================================================
rem  打「下载解压即玩」绿色包：朋友电脑上不用装 Java / Node / MySQL / 浏览器。
rem      pack-portable.cmd
rem  产物：dist\hold-hundred-year-portable-<版本>.zip
rem    解压 -> 双击 hold-hundred-year.exe -> 弹独立游戏窗口；关窗口即退出游戏。
rem
rem  默认自带 Chrome for Testing（约 +170MB 压缩后）。为什么必须自带：实测系统 Edge 有
rem  "启动加速"，会把新窗口交给后台已有的 Edge 进程，我们 spawn 的进程 0 秒退出，
rem  "关窗即退出"就不成立；自带浏览器的进程归我们，关窗才真能退出。
rem  为什么用 Chrome for Testing 而不是 Playwright 的 Chromium：后者是自编译构建，
rem  要求机器装过 VC++ 运行库，实测在未装的机器上 CreateProcess 14001 起不来。
rem  不要自带：set BUNDLE_BROWSER=0（退出就得靠 停止游戏.bat）。
rem
rem  便携包只在"升版本号 + 明确要求打包"两个条件同时满足时才重新生成（见 AGENTS.md）。
rem  本文件与 game.cmd 一样必须存成 GBK(936)：cmd 读 UTF-8 中文批处理会按字节读错位。
rem =========================================================================

set "VER=0.0.1"
set "JAR=Hold-Hundred-Year-%VER%.jar"
set "IN=target\package-in"
set "OUT=target\package-out"
set "APP=hold-hundred-year"
set "CACHE=%LOCALAPPDATA%\hold-hundred-year-build\chrome"
set "MIRROR=https://cdn.npmmirror.com/binaries/chrome-for-testing"

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

echo [4/5] 自带浏览器 ...
if /i "%BUNDLE_BROWSER%"=="0" (
  echo      按要求不自带：关窗口不会退出游戏，退出请用 停止游戏.bat
  goto zip
)
set "CHROME_DIR="
for /d %%d in ("%CACHE%\chrome\win64-*") do if exist "%%d\chrome-win64\chrome.exe" set "CHROME_DIR=%%d\chrome-win64"
if defined CHROME_DIR goto copybrowser
echo      本机没有缓存，正在下载 Chrome for Testing（走 npmmirror 镜像）...
call npx --yes @puppeteer/browsers install chrome@stable --path "%CACHE%" --base-url "%MIRROR%"
for /d %%d in ("%CACHE%\chrome\win64-*") do if exist "%%d\chrome-win64\chrome.exe" set "CHROME_DIR=%%d\chrome-win64"
if not defined CHROME_DIR (
  echo      下载失败。可以先设镜像再重试：
  echo        set PUPPETEER_DOWNLOAD_BASE_URL=%MIRROR%
  echo      或者用 BUNDLE_BROWSER=0 先出不带浏览器的包。
  exit /b 1
)
:copybrowser
echo      复制浏览器进包...
mkdir "%OUT%\%APP%\browser" 2>nul
xcopy /e /i /q /y "%CHROME_DIR%\*" "%OUT%\%APP%\browser\" >nul
if not exist "%OUT%\%APP%\browser\chrome.exe" (echo  浏览器复制失败 & exit /b 1)
rem 安装包用的 setup.exe 用不上，删掉省 7 MB
del /q "%OUT%\%APP%\browser\setup.exe" 2>nul
echo      已自带：%OUT%\%APP%\browser\chrome.exe（关窗口即退出游戏）

:zip
echo [5/5] 附说明、压成 zip ...
> "%OUT%\%APP%\怎么玩.txt" (
  echo  《你持有一百年看看》 绿色版
  echo.
  echo  1. 解压到任意文件夹（路径里有中文和空格都没关系）
  echo  2. 双击 hold-hundred-year.exe
  echo  3. 会弹出一个独立游戏窗口（没有地址栏），里面点"注册"，起个名字就能玩
  echo  4. 关掉那个游戏窗口就是退出游戏；万一没退干净，双击 停止游戏.bat
  echo.
  echo  存档：同目录 data\hold_year.mv.db 这一个文件。复制它=备份，删掉它=重开一局。
  echo  同一局域网里也可以只开一份：别人用 http://你的IP:8080 进来各自注册，互不影响。
  echo  第一次运行 Windows 若提示"已保护你的电脑"：点 更多信息 -> 仍然要运行（未做代码签名）
)
rem 只杀我们自己的进程：自带浏览器也叫 chrome.exe，按映像名杀会把朋友自己的 Chrome 一起干掉
> "%OUT%\%APP%\停止游戏.bat" (
  echo @taskkill /f /im %APP%.exe ^>nul 2^>^&1
  echo @echo 已退出游戏。若游戏窗口还开着，直接点右上角关掉即可。
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