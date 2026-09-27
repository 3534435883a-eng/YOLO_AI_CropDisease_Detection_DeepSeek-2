<#
  一键启动番茄温室智能体（本机 Acer）—— 2026-09-27

  启动链：MySQL(3306) → Flask 向量/识别服务(5000) → Spring Boot 后端(9999)
  三个服务都**先探端口**，已在监听就跳过，因此可以反复执行。

  用法：
    pwsh -File scripts\start-local-agent.ps1                  # 启动后端链
    pwsh -File scripts\start-local-agent.ps1 -Full             # 同时重建 RAG 索引（需 Flask 在线）
    pwsh -File scripts\start-local-agent.ps1 -WithFrontend     # 另起 Vue 开发服务器

  停止：scripts\stop-local-agent.ps1

  ⚠ 调用方式：**直接跑，不要接管道**。
  `pwsh -File scripts\start-local-agent.ps1 | Select-Object -Last 20` 这种写法会"卡住不返回"——
  子进程会继承调用方的输出句柄，管道永远等不到 EOF（服务其实已经起来了）。
  要留输出就重定向到文件：`... *> $env:TEMP\start.log`。

  ⚠ 本机两个必须知道的坑（都踩过）：
  1. `spring-boot:run` **会 fork 一个独立 JVM**——停掉 maven 进程不会停掉后端，9999 仍然占着。
     所以停止脚本按**端口**找 PID，不按进程名。
  2. 本机控制台是 GBK。本文件必须存成 **UTF-8 with BOM**，否则 Windows PowerShell 5.1
     会按 GBK 解码，中文字节被解坏会吃掉引号、报成"字符串缺少终止符"（且行号会随无关改动漂移）。
#>
param(
    [switch]$Full,
    [switch]$WithFrontend
)

$ErrorActionPreference = 'Stop'

$runtime  = 'E:\py\.runtime'
$maven    = Join-Path $runtime 'maven\apache-maven-3.9.16\bin\mvn.cmd'
$settings = Join-Path $runtime 'maven\settings.xml'
$mysqlBin = Join-Path $runtime 'mysql\mysql-8.0.25-winx64\bin'
$mysqlExe = Join-Path $mysqlBin 'mysqld.exe'
$credFile = Join-Path $runtime 'db-credentials.txt'
$keyFile  = Join-Path $runtime 'deepseek-key.txt'
$javaHome = 'C:\Program Files\Java\jdk-11.0.14'
$python   = 'E:\anaconda\envs\learnRag\python.exe'

$project  = Split-Path -Parent $PSScriptRoot
$backend  = Join-Path $project 'YOLO_AI_CropDisease_Detection_SpringBoot'
$flask    = Join-Path $project 'YOLO_AI_CropDisease_Detection_Flask'
$vue      = Join-Path $project 'YOLO_AI_CropDisease_Detection_Vue'
$logDir   = Join-Path $env:TEMP 'agent-logs'

function Write-Step([string]$Tag, [string]$Message, [string]$Color = 'Gray') {
    Write-Host ("[{0,-5}] {1}" -f $Tag, $Message) -ForegroundColor $Color
}

function Test-Port([int]$Port) {
    # 用 netstat 而不是 Get-NetTCPConnection：后者的结果随 shell 的权限/沙箱状态变化，
    # 实测在受限 shell 里对正在监听的端口返回空，会把"已在跑"误报成"未启动"，
    # 进而重复拉起服务。netstat 在各权限下表现一致。
    $pattern = ":$Port\s.*LISTENING"
    return [bool](netstat -ano | Select-String -Pattern $pattern | Select-Object -First 1)
}

function Wait-Port([int]$Port, [int]$Seconds, [string]$What) {
    for ($i = 0; $i -lt $Seconds; $i++) {
        Start-Sleep -Seconds 1
        if (Test-Port $Port) { return $true }
    }
    throw "$What 启动失败：$Seconds 秒内 $Port 仍未监听。日志见 $logDir"
}

function Import-EnvFile([string]$Path, [string]$Label) {
    if (-not (Test-Path $Path)) { return $false }
    Get-Content $Path | Where-Object { $_ -match '^\s*[^#\s]' } | ForEach-Object {
        $parts = $_ -split '=', 2
        if ($parts.Count -eq 2) { Set-Item "Env:$($parts[0].Trim())" $parts[1].Trim() }
    }
    Write-Step 'env' "已载入 $Label（不回显内容）"
    return $true
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

Write-Host '=== 番茄温室智能体 · 一键启动 ===' -ForegroundColor Cyan

# ---------- 1. MySQL ----------
if (Test-Port 3306) {
    Write-Step 'mysql' '3306 已在监听，跳过'
} else {
    Write-Step 'mysql' '正在启动 MySQL…' 'Yellow'
    # ⚠ 必须重定向 stdout/stderr：不加的话 mysqld 会继承调用方的输出管道，
    # 于是任何 `脚本 | Select-Object …` 的写法**永远等不到 EOF**，表现为"脚本卡死"
    # （2026-09-27 真实踩到：服务其实全起来了，是管道不结束）。
    Start-Process -FilePath $mysqlExe -WindowStyle Hidden -ArgumentList @(
        "--basedir=$runtime\mysql\mysql-8.0.25-winx64"
        "--datadir=$runtime\mysql\data"
        '--port=3306'
    ) -RedirectStandardOutput (Join-Path $logDir 'mysql.out.log') `
      -RedirectStandardError  (Join-Path $logDir 'mysql.err.log')
    Wait-Port 3306 40 'MySQL' | Out-Null
    Write-Step 'mysql' '已就绪' 'Green'
}

# ---------- 2. Flask（向量 + 识别）----------
if (Test-Port 5000) {
    Write-Step 'flask' '5000 已在监听，跳过'
} else {
    Write-Step 'flask' '正在启动 Flask（向量服务）…' 'Yellow'
    # 模型已缓存在 ~/.cache/huggingface，离线加载即可；不设这个变量它会去连 huggingface.co（本机不可达）
    $env:HF_HUB_OFFLINE = '1'
    Start-Process -FilePath $python -WorkingDirectory $flask -WindowStyle Hidden `
        -ArgumentList 'main.py' `
        -RedirectStandardOutput (Join-Path $logDir 'flask.out.log') `
        -RedirectStandardError  (Join-Path $logDir 'flask.err.log')
    Wait-Port 5000 60 'Flask' | Out-Null
    Write-Step 'flask' '已就绪（向量检索可用，不会降级为 BM25）' 'Green'
}

# ---------- 3. 环境变量 ----------
if (-not (Import-EnvFile $credFile 'db-credentials.txt')) {
    throw "缺少数据库凭据文件：$credFile"
}
if (-not $env:CROPDISEASE_DB_PASSWORD) {
    throw '凭据文件里没有 CROPDISEASE_DB_PASSWORD，后端会连不上库。'
}
if (-not (Import-EnvFile $keyFile 'deepseek-key.txt')) {
    Write-Step 'warn' "未找到 $keyFile —— 后端仍会启动，但智能体会停在 LLM_ERROR（这是预期行为，不是崩溃）" 'Yellow'
}

# ---------- 4. 后端 ----------
if (Test-Port 9999) {
    Write-Step 'boot' '9999 已在监听，跳过'
} else {
    Write-Step 'boot' '正在启动 Spring Boot…' 'Yellow'
    $env:JAVA_HOME = $javaHome
    $bootArgs = 'spring-boot:run'
    if (-not $Full) {
        # 跳过 RAG 索引重建：知识库已在库里，这一步只是重新 ingest。
        # 不跳过时启动从秒级变成分钟级，且要求 Flask 在线。
        $bootArgs = '-Dspring-boot.run.arguments=--agent.knowledge.bootstrap=false ' + $bootArgs
    }
    Start-Process -FilePath $maven -WorkingDirectory $backend -WindowStyle Hidden `
        -ArgumentList @('-o', '-s', $settings, $bootArgs) `
        -RedirectStandardOutput (Join-Path $logDir 'backend.out.log') `
        -RedirectStandardError  (Join-Path $logDir 'backend.err.log')
    Wait-Port 9999 180 '后端' | Out-Null
    Write-Step 'boot' '已就绪' 'Green'
}

# ---------- 5. 可选前端 ----------
if ($WithFrontend) {
    # 端口取自 Vue 项目的 .env（VITE_PORT = 8100），不是猜的。
    # 路由是 hash 模式，对话页为 http://localhost:8100/#/agentChat
    if (Test-Port 8100) {
        Write-Step 'vue' '8100 已在监听，跳过'
    } else {
        Write-Step 'vue' '正在启动 Vue 开发服务器…' 'Yellow'
        Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'npm run dev' -WorkingDirectory $vue -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logDir 'vue.out.log') `
            -RedirectStandardError  (Join-Path $logDir 'vue.err.log')
        Wait-Port 8100 90 '前端' | Out-Null
        Write-Step 'vue' '已就绪 → http://localhost:8100/#/agentChat' 'Green'
    }
}

# ---------- 汇总 ----------
Write-Host ''
Write-Host '=== 就绪 ===' -ForegroundColor Green
Write-Host '  对话页面（需前端在跑，加 -WithFrontend）: ' -NoNewline
Write-Host 'http://localhost:8100/#/agentChat' -ForegroundColor Cyan
Write-Host ''
Write-Host '  这些可以直接用浏览器打开（GET）：'
Write-Host '    知识库状态            : http://localhost:9999/ai/knowledge/status'
Write-Host '    生产规划报告(Markdown): http://localhost:9999/ai/agent/report'
Write-Host ''
# ⚠ 这一条特别说明：/ai/agent/chat 是 **POST + SSE**，浏览器直接打开会得到
#   "Request method ''GET'' not supported" 的 405 页——那是正常的，不是系统故障。
#   首版脚本把它印成可点击地址，用户照点必然撞上 405，故在此改掉。
Write-Host '  智能体对话接口（POST + SSE，浏览器直接打开会报 405，这是正常的）：' -ForegroundColor Yellow
Write-Host '    POST http://localhost:9999/ai/agent/chat'
Write-Host '    body: {"sessionId":"1","question":"番茄早疫病怎么防治？","crop":"番茄"}'
Write-Host '    PowerShell 里试一发（中文必须走 UTF-8 字节，否则 Jackson 会报 Invalid UTF-8）：' -ForegroundColor DarkGray
Write-Host '      $b = [Text.Encoding]::UTF8.GetBytes(''{"sessionId":"1","question":"番茄早疫病怎么防治？","crop":"番茄"}'');' -ForegroundColor DarkGray
Write-Host '      (Invoke-WebRequest http://localhost:9999/ai/agent/chat -Method POST -ContentType application/json -Body $b).Content' -ForegroundColor DarkGray
Write-Host ''
Write-Host '  日志目录                : ' -NoNewline; Write-Host $logDir -ForegroundColor DarkGray
Write-Host '  停止全部                : ' -NoNewline; Write-Host 'pwsh -File scripts\stop-local-agent.ps1' -ForegroundColor DarkGray
Write-Host ''
Write-Host '  提示：智能体需要一个正在进行的「模拟运行」才有棚内状态可依据；' -ForegroundColor DarkGray
Write-Host '        库里已有历史运行，若状态接口报无活动运行，先在指挥中心创建一个。' -ForegroundColor DarkGray
