[CmdletBinding()]
param()

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

$projectRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent (Split-Path -Parent $projectRoot)
$runtimeRoot = Join-Path $workspaceRoot '.runtime'
$backendRoot = Join-Path $projectRoot 'YOLO_AI_CropDisease_Detection_SpringBoot'
$frontendRoot = Join-Path $projectRoot 'YOLO_AI_CropDisease_Detection_Vue'

# MySQL on this machine cannot reliably parse the Chinese workspace path. The junction
# gives the server an ASCII-only path while continuing to use the original data files.
$runtimeLink = Join-Path $env:LOCALAPPDATA 'TomatoGreenhouseRuntime'
if (-not (Test-Path -LiteralPath $runtimeLink)) {
    New-Item -ItemType Junction -Path $runtimeLink -Target $runtimeRoot | Out-Null
}

$mysqlHome = Join-Path $runtimeLink 'mysql\mysql-8.4.11-winx64'
$mysqlExe = Join-Path $mysqlHome 'bin\mysqld.exe'
$mysqlClient = Join-Path $mysqlHome 'bin\mysql.exe'
$mysqlData = Join-Path $runtimeLink 'mysql\data'
$javaHome = Join-Path $runtimeRoot 'java\temurin8'
$mavenExe = Join-Path $runtimeRoot 'maven\apache-maven-3.9.16\bin\mvn.cmd'
$viteEntry = Join-Path $frontendRoot 'node_modules\vite\bin\vite.js'
$nodeCandidates = @(
    (Get-Command node.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    (Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe')
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
$nodeExe = $nodeCandidates | Select-Object -First 1

foreach ($required in @($mysqlExe, $mysqlClient, $mysqlData, $javaHome, $mavenExe, $backendRoot, $frontendRoot, $viteEntry, $nodeExe)) {
    if ([string]::IsNullOrWhiteSpace($required) -or -not (Test-Path -LiteralPath $required)) {
        throw "缺少启动依赖：$required"
    }
}

$mysqlReady = Test-LocalPort -Port 3306
$backendReady = Test-LocalPort -Port 9999
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
            throw 'MySQL 未能在 20 秒内启动，请查看 .runtime/mysql/data/mysqld-local.err。'
        }
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
            Get-Content -Raw -LiteralPath $migration.FullName | & $mysqlClient --protocol=TCP -h 127.0.0.1 -P 3306 -u $dbUser cropdisease
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

if (-not $backendReady) {
    Write-Host '启动 Spring Boot 后端...'
    $backendCommand = "Set-Location -LiteralPath '$backendRoot'; & '$mavenExe' spring-boot:run"
    $encodedBackendCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($backendCommand))
    Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encodedBackendCommand
    ) -WindowStyle Hidden | Out-Null
    if (-not (Wait-LocalPort -Port 9999 -TimeoutSeconds 90)) {
        throw 'Spring Boot 后端未能在 90 秒内启动。请确认数据库账号配置和 Maven 依赖。'
    }
}

if (-not (Test-LocalPort -Port 8100)) {
    Write-Host '启动 Vue 前端...'
    $frontendCommand = "Set-Location -LiteralPath '$frontendRoot'; & '$nodeExe' '$viteEntry' --host 127.0.0.1"
    $encodedFrontendCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($frontendCommand))
    Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encodedFrontendCommand
    ) -WindowStyle Hidden | Out-Null
    if (-not (Wait-LocalPort -Port 8100 -TimeoutSeconds 45)) {
        throw 'Vue 前端未能在 45 秒内启动。请确认 node_modules 已安装。'
    }
}

$url = 'http://localhost:8100/index.html#/agentCenter'
Start-Process $url
Write-Host "平台已启动：$url"
