@echo off
chcp 936 >nul
setlocal enabledelayedexpansion
title 中医电子病历质控系统 - 启动控制台

rem ==================================================================
rem  中医电子病历质控与标准化系统 - 启动控制台（单窗口）
rem
rem  用法：
rem    start-all.bat          启动全部服务，进入实时状态面板
rem    start-all.bat check    只做环境预检，不启动任何服务
rem
rem  特点：
rem    - 只开这一个窗口，三个服务在后台静默运行，不弹新窗口
rem    - 各服务日志分别写入 logs\dev\*.log
rem    - 面板内按 Q 停止本次启动的服务并退出，按 O 打开浏览器
rem    - 启动前已在运行的实例会被跳过，退出时也不会被停止
rem    - 直接关闭本窗口 = 强制结束全部服务（不做优雅停机）
rem
rem  注意：本文件必须保持 GBK 编码 + CRLF 行尾，否则 cmd 会按
rem        当前代码页误解析，导致中文错位、行被劈开。
rem ==================================================================

rem ================= 0. 路径与参数 =================
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "BACKEND=%ROOT%\java-backend"
set "FRONTEND=%ROOT%\frontend"
set "NLPDIR=%ROOT%\python-nlp"
set "LOGDIR=%ROOT%\logs\dev"
set "MVN=D:\devSoft\apache-maven-3.9.16\bin\mvn.cmd"
set "JDK=F:\jdk24"
set "JAVA_HOME=%JDK%"
set "NLP_CMD=%NLPDIR%\.venv\Scripts\uvicorn.exe main:app --host 127.0.0.1 --port 8001"

rem 依赖已全部缓存时保留 -o（离线解析、启动更快）；
rem 若后端日志报「缺失构件 / Cannot access ... in offline mode」则改为空串。
set "MVN_OFFLINE=-o"
set "MVN_CMD=%MVN% %MVN_OFFLINE% spring-boot:run"

set "MODE=%~1"
if "%MODE%"=="" set "MODE=all"

if not exist "%LOGDIR%" mkdir "%LOGDIR%" >nul 2>&1

rem ================= 1. 环境预检 =================
set "FAIL=0"

if not exist "%MVN%" (
    echo [错误] 找不到 Maven  : %MVN%
    set "FAIL=1"
)
if not exist "%JDK%\bin\java.exe" (
    echo [错误] 找不到 JDK    : %JDK%
    set "FAIL=1"
)
if not exist "%BACKEND%\pom.xml" (
    echo [错误] 找不到后端    : %BACKEND%\pom.xml
    set "FAIL=1"
)
if not exist "%FRONTEND%\package.json" (
    echo [错误] 找不到前端    : %FRONTEND%\package.json
    set "FAIL=1"
)
if not exist "%NLPDIR%\.venv\Scripts\uvicorn.exe" (
    echo [错误] 找不到 NLP 虚拟环境 : %NLPDIR%\.venv\Scripts\uvicorn.exe
    echo        首次使用请先执行：python -m venv .venv 并安装 requirements.txt
    set "FAIL=1"
)
where npm >nul 2>&1
if errorlevel 1 (
    echo [错误] PATH 中找不到 npm
    set "FAIL=1"
)

if "%FAIL%"=="1" (
    echo.
    echo [中止] 预检未通过，请先修复以上错误。
    pause
    exit /b 1
)

if not exist "%FRONTEND%\node_modules" (
    echo [执行] 前端依赖缺失，正在 npm install ...
    pushd "%FRONTEND%"
    call npm install
    popd
)

if /i "%MODE%"=="check" goto :do_check

rem ================= 2. 启动服务（后台静默，不弹窗口） =================
del /q "%LOGDIR%\*.log" >nul 2>&1

call :launch "NLP 抽取" 8001 "%NLPDIR%"  "%NLP_CMD%"     "nlp.log"
call :launch "后端 API" 8080 "%BACKEND%" "%MVN_CMD%"    "backend.log"
call :launch "前端 Web" 3000 "%FRONTEND%" "npm run dev" "frontend.log"

rem ================= 3. 实时状态面板 =================
set /a ELAPSED=0
set "BROWSER_DONE=0"

:panel
cls
echo.
echo     =========================================================
echo     中医电子病历质控与标准化系统 - 启动控制台
echo     =========================================================
echo.
echo     服务             端口     状态
echo     ---------------------------------------------------------
call :show "NLP 抽取" 8001
call :show "后端 API" 8080
call :show "前端 Web" 3000
echo.
echo     依赖服务
call :portinuse 3306
if "!IN_USE!"=="1" (echo     MySQL          :3306      已就绪) else (echo     MySQL          :3306      未监听)
call :portinuse 6379
if "!IN_USE!"=="1" (echo     Redis          :6379      已就绪) else (echo     Redis          :6379      未监听)
call :portinuse 9200
if "!IN_USE!"=="1" (echo     Elasticsearch  :9200      已就绪) else (echo     Elasticsearch  :9200      未监听)
echo.
echo     ---------------------------------------------------------
echo     访问地址 : http://localhost:3000
echo     演示账号 : admin / auditor      密码 : 123456
echo     日志目录 : %LOGDIR%
echo.
echo     [Q] 停止本次启动的服务并退出      [O] 打开浏览器      [自动刷新 1 秒]
echo     本次启动前已在运行的实例，退出时不会被停止
echo.
set /a ELAPSED+=1

call :portinuse 3000
if "!IN_USE!"=="1" if "!BROWSER_DONE!"=="0" (
    start "" "http://localhost:3000"
    set "BROWSER_DONE=1"
)

choice /C QOR /T 1 /D R /N >nul
if errorlevel 3 goto :panel
if errorlevel 2 goto :open_browser
goto :quit

:open_browser
start "" "http://localhost:3000"
goto :panel

rem ================= 4. 停止全部并退出 =================
:quit
cls
echo.
echo     正在停止本次启动的服务 ...
call :killport 8001
call :killport 8080
call :killport 3000
echo     已完成。窗口将在 3 秒后关闭。
ping -n 4 127.0.0.1 >nul
exit /b 0

rem ================= check 模式 =================
:do_check
echo [ OK ] 路径与工具链检查通过
call :portinuse 3306
if "!IN_USE!"=="1" (echo [ OK ] MySQL         :3306 已就绪) else (echo [警告] MySQL         :3306 未监听，后端可能启动失败)
call :portinuse 6379
if "!IN_USE!"=="1" (echo [ OK ] Redis         :6379 已就绪) else (echo [警告] Redis         :6379 未监听，后端可能启动失败)
call :portinuse 9200
if "!IN_USE!"=="1" (echo [ OK ] Elasticsearch :9200 已就绪) else (echo [警告] Elasticsearch :9200 未监听，术语归一将返回 503)
echo.
echo ---------------- 以下服务将被启动 ----------------
call :portinuse 8001
if "!IN_USE!"=="1" (echo   NLP 抽取 :8001  ^(已在运行，跳过^)) else (echo   NLP 抽取 :8001  %NLP_CMD%)
call :portinuse 8080
if "!IN_USE!"=="1" (echo   后端     :8080  ^(已在运行，跳过^)) else (echo   后端     :8080  %MVN_CMD%)
call :portinuse 3000
if "!IN_USE!"=="1" (echo   前端     :3000  ^(已在运行，跳过^)) else (echo   前端     :3000  npm run dev)
echo ------------------------------------------------
echo.
echo [完成] 预检结束（check 模式，未启动任何服务）。
exit /b 0

rem ================= 子程序 =================

rem 启动一个服务：%1=名称 %2=端口 %3=工作目录 %4=命令 %5=日志文件
rem 端口已被占用则跳过，并把状态记到 ST_<端口>（供 :killport 判断是否由本脚本启动）
:launch
call :portinuse %2
if "!IN_USE!"=="1" (
    set "ST_%2=SKIP"
    exit /b 0
)
start "" /B /D "%~3" cmd /c "%~4 > "%LOGDIR%\%~5" 2>&1"
set "ST_%2=START"
exit /b 0

rem 面板输出一行服务状态：%1=名称 %2=端口
:show
set "STAT=启动中（!ELAPSED! 秒）"
if !ELAPSED! GEQ 90 set "STAT=未就绪（见 logs\dev）"
if "!ST_%2!"=="SKIP" set "STAT=跳过（启动前已在运行）"
call :portinuse %2
if "!IN_USE!"=="1" set "STAT=就绪"
echo     %~1         %2     !STAT!
exit /b 0

rem 停止本脚本启动过的服务：%1=端口（未由本脚本启动则不动）
:killport
if not "!ST_%1!"=="START" exit /b 0
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /C:"LISTENING" ^| findstr /C:":%1 "') do (
    taskkill /F /T /PID %%p >nul 2>&1
)
exit /b 0

rem 判断端口是否被占用：%1=端口号，结果写入 IN_USE（1=占用 / 0=空闲）
:portinuse
netstat -ano | findstr /C:"LISTENING" | findstr /C:":%~1 " >nul 2>&1
if errorlevel 1 (set "IN_USE=0") else (set "IN_USE=1")
exit /b 0
