# 一键启动本地平台：MySQL(3306) -> Flask 识别/向量服务(5000) -> Spring Boot 后端(9999) -> Vue 前端(8100)
#
# 双击桌面"启动农业智能体平台"即可；服务输出写在 %LOCALAPPDATA%\TomatoGreenhouseRuntime\logs。
# 重复双击是安全的：已在监听的端口会被跳过（幂等）。
#
# ！！本文件必须保存为 **带 UTF-8 BOM** 的 UTF-8 ！！
# Windows PowerShell 5.1（双击 .lnk 时用的就是这个解释器）对**无 BOM** 的文件按系统 ANSI
# 代码页（本机为 GBK）解码，文件里的中文会被拆成乱码字节，其中某些字节会撞上引号，
# 导致整个脚本报"字符串缺少终止符"而一行都不执行——这正是"双击启动没反应"的根因。
# 用编辑器改完请确认 BOM 仍在（首三字节 EF BB BF）。
[CmdletBinding()]
param(
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'

function Test-LocalPort {
    param([Parameter(Mandatory = $true)][int]$Port)

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $connection = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $connection.AsyncWaitHandle.WaitOne(300)) {
            return $false
        }
        $client.EndConnect($connection)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-LocalPort {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-LocalPort -Port $Port) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Get-ConfiguredEnvironmentValue {
    param([Parameter(Mandatory = $true)][string]$Name)

    foreach ($scope in @('Process', 'User', 'Machine')) {
        $value = [Environment]::GetEnvironmentVariable($Name, $scope)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            return $value
        }
    }
    return $null
}

function Read-DatabasePassword {
    $securePassword = Read-Host '数据库密码' -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

# 把每个服务的输出落到文件：原实现一律 WindowStyle Hidden 且不留日志，
# 启动失败时用户只看到一个闪过的窗口，无从判断卡在哪一步（这正是"启动不了"最难查的形态）。
function Start-LoggedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$LogPath,
        [string]$ErrorLogPath
    )

    if ([string]::IsNullOrWhiteSpace($ErrorLogPath)) {
        $ErrorLogPath = "$LogPath.err"
    }
    # 自动给含空格的参数加引号：Start-Process 的 -ArgumentList 是**按空格拼接**的，
    # 而工作区路径是 "E:\agent 农业\..."，直接把 vite 入口路径丢进去会被截成 "E:\agent"，
    # node 随即报 `Cannot find module 'E:\agent'`（实测踩过这一次）。
    $quoted = @()
    foreach ($argument in $ArgumentList) {
        if ($argument -match '\s' -and -not $argument.StartsWith('"')) {
            $quoted += ('"{0}"' -f $argument)
        } else {
            $quoted += $argument
        }
    }
    Start-Process -FilePath $FilePath -ArgumentList $quoted -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $LogPath -RedirectStandardError $ErrorLogPath -WindowStyle Hidden | Out-Null
}

try {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $workspaceRoot = Split-Path -Parent (Split-Path -Parent $projectRoot)
    $runtimeRoot = Join-Path $workspaceRoot '.runtime'
    $backendRoot = Join-Path $projectRoot 'YOLO_AI_CropDisease_Detection_SpringBoot'
    $frontendRoot = Join-Path $projectRoot 'YOLO_AI_CropDisease_Detection_Vue'
    $flaskRoot = Join-Path $projectRoot 'YOLO_AI_CropDisease_Detection_Flask'

    # MySQL on this machine cannot reliably parse the Chinese workspace path. The junction
    # gives the server an ASCII-only path while continuing to use the original data files.
    $runtimeLink = Join-Path $env:LOCALAPPDATA 'TomatoGreenhouseRuntime'
    if (-not (Test-Path -LiteralPath $runtimeLink)) {
        New-Item -ItemType Junction -Path $runtimeLink -Target $runtimeRoot | Out-Null
    }

    $logRoot = Join-Path $runtimeLink 'logs'
    if (-not (Test-Path -LiteralPath $logRoot)) {
        New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
    }

    $mysqlHome = Join-Path $runtimeLink 'mysql\mysql-8.4.11-winx64'
    $mysqlExe = Join-Path $mysqlHome 'bin\mysqld.exe'
    $mysqlClient = Join-Path $mysqlHome 'bin\mysql.exe'
    $mysqlData = Join-Path $runtimeLink 'mysql\data'
    $javaHome = Join-Path $runtimeRoot 'java\temurin8'
    $mavenExe = Join-Path $runtimeRoot 'maven\apache-maven-3.9.16\bin\mvn.cmd'
    $viteEntry = Join-Path $frontendRoot 'node_modules\vite\bin\vite.js'
    $flaskEntry = Join-Path $flaskRoot 'main.py'
    $nodeCandidates = @(
        (Get-Command node.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
        (Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe')
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    $nodeExe = $nodeCandidates | Select-Object -First 1
    # 本机默认 python 是 3.3.5（不支持 f-string），不能直接用 PATH 上的 python。
    $pythonCandidates = @(
        'C:\Users\nom\AppData\Local\Programs\Python\Python312\python.exe',
        'C:\Users\nom\AppData\Local\Programs\Python\Python311\python.exe',
        (Get-Command python.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue)
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    $pythonExe = $pythonCandidates | Select-Object -First 1

    foreach ($required in @($mysqlExe, $mysqlClient, $mysqlData, $javaHome, $mavenExe, $backendRoot,
                            $frontendRoot, $viteEntry, $nodeExe, $flaskRoot, $flaskEntry, $pythonExe)) {
        if ([string]::IsNullOrWhiteSpace($required) -or -not (Test-Path -LiteralPath $required)) {
            throw "缺少启动依赖：$required"
        }
    }

    $mysqlReady = Test-LocalPort -Port 3306
    $flaskReady = Test-LocalPort -Port 5000
    $backendReady = Test-LocalPort -Port 9999
    $frontendReady = Test-LocalPort -Port 8100

    if (-not $mysqlReady -or -not $backendReady) {
        $dbUser = Get-ConfiguredEnvironmentValue -Name 'CROPDISEASE_DB_USER'
        $dbPassword = Get-ConfiguredEnvironmentValue -Name 'CROPDISEASE_DB_PASSWORD'
        if ([string]::IsNullOrWhiteSpace($dbUser)) {
            $dbUser = Read-Host '数据库用户名'
        }
        if ([string]::IsNullOrWhiteSpace($dbPassword)) {
            $dbPassword = Read-DatabasePassword
        }
        if ([string]::IsNullOrWhiteSpace($dbUser) -or [string]::IsNullOrWhiteSpace($dbPassword)) {
            throw '数据库用户名或密码不能为空。'
        }

        $env:CROPDISEASE_DB_USER = $dbUser
        $env:CROPDISEASE_DB_PASSWORD = $dbPassword
        $env:JAVA_HOME = $javaHome

        if (-not $mysqlReady) {
            Write-Host '启动本地 MySQL...'
            Start-Process -FilePath $mysqlExe -ArgumentList @(
                '--no-defaults',
                "--basedir=$mysqlHome",
                "--datadir=$mysqlData",
                '--port=3306',
                '--bind-address=127.0.0.1',
                "--pid-file=$mysqlData\mysqld-local.pid",
                "--log-error=$mysqlData\mysqld-local.err"
            ) -WindowStyle Hidden | Out-Null
            if (-not (Wait-LocalPort -Port 3306 -TimeoutSeconds 20)) {
                throw "MySQL 未能在 20 秒内启动，请查看 $mysqlData\mysqld-local.err。"
            }
            Write-Host '  MySQL 就绪 (3306)'
        }

        # Apply every migration in filename order. All of them are create-only
        # (CREATE TABLE IF NOT EXISTS) and never rewrite the legacy tables, so
        # re-running this script is safe and self-healing.
        $migrationDir = Join-Path $projectRoot 'database\migrations'
        $migrations = @(Get-ChildItem -LiteralPath $migrationDir -Filter '*.sql' -ErrorAction SilentlyContinue | Sort-Object Name)
        if ($migrations.Count -eq 0) {
            throw "未找到数据库迁移文件：$migrationDir"
        }
        $env:MYSQL_PWD = $dbPassword
        try {
            foreach ($migration in $migrations) {
                Write-Host "应用迁移 $($migration.Name)..."
                # mysql 必须直接读取**文件字节**：PowerShell 管道会按控制台编码重新编码，
                # 含中文数据的迁移会被写坏（实测 '苹果' 被写成 '鑻规灉'）。cmd 的输入重定向保留原始字节。
                $mysqlCommand = '"{0}" --protocol=TCP -h 127.0.0.1 -P 3306 -u {1} --default-character-set=utf8mb4 cropdisease < "{2}"' -f $mysqlClient, $dbUser, $migration.FullName
                & cmd.exe /c $mysqlCommand
                if ($LASTEXITCODE -ne 0) {
                    throw "数据库迁移失败（$($migration.Name)），退出码：$LASTEXITCODE"
                }
            }
        } finally {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        }

        # The agent needs LLM credentials. They are only ever read from the
        # environment (User scope included) and forwarded to the child process;
        # nothing is written to disk, matching application.properties.
        foreach ($name in @('DEEPSEEK_API_KEY', 'DEEPSEEK_BASE_URL', 'DEEPSEEK_MODEL')) {
            $value = Get-ConfiguredEnvironmentValue -Name $name
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                Set-Item -Path "Env:$name" -Value $value
            }
        }
        if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
            Write-Host '提示：未检测到大模型密钥（DEEPSEEK_API_KEY），AI 决策对话会返回"AI 服务尚未配置"（其余功能不受影响）。' -ForegroundColor Yellow
        }
    }

    # 顺序有讲究：Flask 必须**先于后端**起来并预热。
    # 向量模型是懒加载的，冷启动首次 /embed 要约 8 秒，而后端 embedding 超时是 3 秒——
    # 若后端先起并触发一次 ingest/检索，那一次必然超时降级（实测现象：向量召回整段缺失）。
    if (-not $flaskReady) {
        Write-Host '启动 Flask 识别/向量服务...'
        $env:HF_HUB_OFFLINE = '1'          # 本机 HuggingFace 不可达，必须离线，否则冷启动探测重试约 23 秒
        $env:PYTHONIOENCODING = 'utf-8'    # 日志里有中文，避免控制台 GBK 编码报错
        Start-LoggedProcess -FilePath $pythonExe -ArgumentList @('main.py') -WorkingDirectory $flaskRoot `
            -LogPath (Join-Path $logRoot 'flask.log')
        if (-not (Wait-LocalPort -Port 5000 -TimeoutSeconds 60)) {
            throw "Flask 未能在 60 秒内启动，请查看 $logRoot\flask.log。"
        }
        Write-Host '  Flask 就绪 (5000)'
    }

    # 预热向量模型：第一次 /embed 会加载模型，给足时间（不需要它成功，只要模型进内存）。
    Write-Host '预热向量模型（首次约 8 秒，之后 14~32 ms）...'
    try {
        $warmup = Invoke-RestMethod -Uri 'http://127.0.0.1:5000/embed' -Method Post `
            -Body '{"texts":["平台启动预热"]}' -ContentType 'application/json' -TimeoutSec 180
        if ($warmup.vectors.Count -ge 1) {
            Write-Host "  向量模型已就绪（$($warmup.vectors[0].Count) 维）"
        } else {
            Write-Host '  警告：/embed 未返回向量，检索将降级为纯关键词。' -ForegroundColor Yellow
        }
    } catch {
        Write-Host "  警告：向量服务预热失败（$($_.Exception.Message)），检索将降级为纯关键词。" -ForegroundColor Yellow
    }

    if (-not $backendReady) {
        Write-Host '启动 Spring Boot 后端...'
        Start-LoggedProcess -FilePath $mavenExe -ArgumentList @('spring-boot:run') -WorkingDirectory $backendRoot `
            -LogPath (Join-Path $logRoot 'backend.log')
        if (-not (Wait-LocalPort -Port 9999 -TimeoutSeconds 120)) {
            throw "Spring Boot 后端未能在 120 秒内启动，请查看 $logRoot\backend.log。"
        }
        Write-Host '  后端就绪 (9999)'
    }

    if (-not $frontendReady) {
        Write-Host '启动 Vue 前端...'
        Start-LoggedProcess -FilePath $nodeExe -ArgumentList @($viteEntry, '--host', '127.0.0.1') `
            -WorkingDirectory $frontendRoot -LogPath (Join-Path $logRoot 'frontend.log')
        if (-not (Wait-LocalPort -Port 8100 -TimeoutSeconds 60)) {
            throw "Vue 前端未能在 60 秒内启动，请查看 $logRoot\frontend.log。"
        }
        Write-Host '  前端就绪 (8100)'
    }

    $base = 'http://localhost:8100/index.html'
    Write-Host ''
    Write-Host '全部就绪，可访问：' -ForegroundColor Green
    Write-Host "  智能体对话   $base#/agentChat"
    Write-Host "  视觉知识覆盖 $base#/visionCoverage"
    Write-Host "  智能体中心   $base#/agentCenter"
    Write-Host "  知识库状态   http://127.0.0.1:9999/ai/knowledge/status"
    Write-Host "  日志目录     $logRoot"
    Write-Host ''
    # 实测（冷启动后连续提问）：第 1 问约 35 秒、第 2 问约 22 秒、之后稳定在 6 秒上下。
    # 差异来自 JVM 预热与首次外网 TLS/提示词缓存，不是故障——不提示的话演示时容易以为卡死。
    Write-Host '提示：启动后第一次提问约需 35 秒，第二次约 22 秒，之后每次约 6 秒（实测）。' -ForegroundColor DarkGray
    Write-Host ''

    if (-not $NoBrowser) {
        Start-Process "$base#/agentChat"
    }
} catch {
    Write-Host ''
    Write-Host "启动失败：$($_.Exception.Message)" -ForegroundColor Red
    if (Test-Path -LiteralPath (Join-Path $env:LOCALAPPDATA 'TomatoGreenhouseRuntime\logs')) {
        Write-Host "日志目录：$(Join-Path $env:LOCALAPPDATA 'TomatoGreenhouseRuntime\logs')" -ForegroundColor Yellow
    }
    Write-Host '按回车关闭此窗口...' -ForegroundColor Yellow
    Read-Host | Out-Null
    exit 1
}
