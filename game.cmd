@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem =========================================================================
rem  《你持有一百年看看》一键脚本 —— 跑在自己电脑上，不需要云服务器
rem    game.cmd              启动游戏（没打包过会先自动打包）
rem    game.cmd build        重新打包：前端 -> 后端 jar
rem    game.cmd lan          打印要发给朋友的地址
rem    game.cmd backup       把存档导出到 backups 目录
rem    game.cmd restore 文件 用导出的文件覆盖存档（先停游戏）
rem  数据库密码/端口等用环境变量覆盖，详见 README「给朋友玩（没有云服务器）」。
rem  注意：本文件按 GBK(代码页936) 保存。cmd 读 UTF-8 中文批处理会按字节错位，
rem  表现为某行注释被当成命令执行（"xxx 不是内部或外部命令"）。别把它改成 UTF-8。
rem =========================================================================

set "JAR=target\Hold-Hundred-Year-0.0.5.jar"
set "DB=hold_year"
set "MYSQLBIN=C:\Program Files\MySQL\MySQL Server 8.0\bin"
set "PORT=%HOLD_PORT%"
if "%PORT%"=="" set "PORT=8080"

set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%i in ('where java 2^>nul') do if not defined JAVA set "JAVA=%%i"
if not defined JAVA for /d %%d in ("%ProgramFiles%\Microsoft\jdk-*") do if not defined JAVA if exist "%%d\bin\java.exe" set "JAVA=%%d\bin\java.exe"

if "%~1"=="" goto start
if /i "%~1"=="start" goto start
if /i "%~1"=="build" goto build
if /i "%~1"=="lan" goto lan
if /i "%~1"=="backup" goto backup
if /i "%~1"=="restore" goto restore
goto usage

:start
if not defined JAVA goto no_java
if not exist "%JAR%" goto need_build
echo.
echo  启动中...... 你自己玩：http://localhost:%PORT%
echo  朋友玩：http://本机局域网地址:%PORT%   看不清地址就另开窗口跑 game.cmd lan
echo  停止：在这个窗口按 Ctrl+C，或者直接关掉窗口。
echo.
"%JAVA%" -jar "%JAR%"
goto end

:need_build
echo  还没打包过，先打一个（第一次比较慢，要装前端依赖）。
call :build || goto end
goto start

:build
if not defined JAVA goto no_java
echo [1/3] 构建前端 ...
pushd frontend
if not exist node_modules call npm install || (popd & echo  npm install 失败 & exit /b 1)
call npm run build || (popd & echo  前端构建失败 & exit /b 1)
popd
echo [2/3] 把前端塞进后端的资源目录 ...
if exist "src\main\resources\static" rmdir /s /q "src\main\resources\static"
xcopy /e /i /q "frontend\dist" "src\main\resources\static" >nul || (echo  拷贝前端产物失败 & exit /b 1)
rem 清掉上一次构建留在 target 里的静态资源：Maven 只覆盖同名文件、不删旧文件，
rem 否则已经删掉的页面（例如旧登录页）会跟着新包一起进 jar。
if exist "target\classes\static" rmdir /s /q "target\classes\static"
echo [3/3] 打包 jar（跳过测试；要跑测试请用 mvnw test）...
call mvnw.cmd -q package -DskipTests
if not errorlevel 1 goto packaged
echo  mvnw.cmd 失败了（本机已知问题），改用 wrapper 已下载的 Maven ...
set "MVN="
for /f "delims=" %%m in ('dir /b /s "%USERPROFILE%\.m2\wrapper\dists\mvn.cmd" 2^>nul') do set "MVN=%%m"
if not defined MVN (
  echo  也没找到 wrapper 里的 Maven：先手动跑一次 mvnw.cmd -v 让它下载，或装个 Maven。
  exit /b 1
)
call "%MVN%" -q package -DskipTests
if errorlevel 1 (echo  Maven 打包失败，看上面的报错 & exit /b 1)
:packaged
echo  打包完成：%JAR%
exit /b 0

:lan
echo.
echo  本机局域网地址（发给朋友用这个，端口 %PORT%）：
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
  set "IP=%%a"
  set "IP=!IP: =!"
  echo     http://!IP!:%PORT%
)
echo.
echo  朋友打不开，多半是 Windows 防火墙拦了入站。用管理员身份的命令提示符放行一次：
echo     netsh advfirewall firewall add rule name="Hold Hundred Year" dir=in action=allow protocol=TCP localport=%PORT%
goto end

:backup
if not exist "%MYSQLBIN%\mysqldump.exe" goto no_mysql
if not exist backups mkdir backups
set "STAMP=%date:~0,4%%date:~5,2%%date:~8,2%-%time:~0,2%%time:~3,2%"
set "STAMP=!STAMP: =0!"
"%MYSQLBIN%\mysqldump.exe" -uroot -p%HOLD_DB_PASSWORD% --default-character-set=utf8mb4 --single-transaction %DB% > "backups\%DB%-!STAMP!.sql"
if errorlevel 1 (echo  导出失败：先 set $env:HOLD_DB_PASSWORD=你的MySQL密码 & exit /b 1)
echo  已导出 backups\%DB%-!STAMP!.sql
goto end

:restore
if "%~2"=="" (echo  用法： game.cmd restore backups\某个.sql & goto end)
if not exist "%MYSQLBIN%\mysql.exe" goto no_mysql
"%MYSQLBIN%\mysql.exe" -uroot -p%HOLD_DB_PASSWORD% --default-character-set=utf8mb4 %DB% < "%~2"
echo  已导入 %~2 （导入前请先停掉游戏）
goto end

:no_java
echo  没找到 Java 17：装一个 JDK 17，或把 JAVA_HOME 指过去。
goto end

:no_mysql
echo  没找到 MySQL 命令行工具（默认找 %MYSQLBIN%）。
goto end

:usage
echo  用法： game.cmd [start^|build^|lan^|backup^|restore 文件]
goto end

:end
endlocal