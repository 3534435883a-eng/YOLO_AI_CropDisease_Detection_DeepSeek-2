<#
  本机（Acer）后端启动脚本 —— 2026-09-26 实测可用。

  与 scripts/start-local-platform.ps1 的区别：那个是给**另一台机器**写的
  （硬编码 C:\Users\nom\... 与 E:\agent 农业\...），在本机跑不通，别依赖它。
  本脚本只负责 MySQL 检查 + 环境变量 + 后端，路径全部按本机实际布局。

  用法：
    pwsh -File scripts\start-local-backend.ps1            # 推荐；跳过 RAG 索引构建
    pwsh -File scripts\start-local-backend.ps1 -Full      # 构建 RAG 索引（需 Flask 向量服务在跑）

  密钥：把 DEEPSEEK_API_KEY 写进 E:\py\.runtime\deepseek-key.txt（形如 KEY=值），
        或用 -Key 参数传入。脚本从不回显密钥内容。
        未配置时后端仍能启动，但智能体会停在 LLM_ERROR（这是预期行为，不是故障）。
#>
param(
    [switch]$Full,
    [string]$Key
)

$ErrorActionPreference = 'Stop'

$runtime  = 'E:\py\.runtime'
$maven    = Join-Path $runtime 'maven\apache-maven-3.9.16\bin\mvn.cmd'
$settings = Join-Path $runtime 'maven\settings.xml'
$mysqlBin = Join-Path $runtime 'mysql\mysql-8.0.25-winx64\bin'
$credFile = Join-Path $runtime 'db-credentials.txt'
$keyFile  = Join-Path $runtime 'deepseek-key.txt'
$javaHome = 'C:\Program Files\Java\jdk-11.0.14'

$project = Split-Path -Parent $PSScriptRoot
$module  = Join-Path $project 'YOLO_AI_CropDisease_Detection_SpringBoot'

function Import-EnvFile {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path $Path)) { return $false }
    Get-Content $Path | Where-Object { $_ -match '^\s*[^#\s]' } | ForEach-Object {
        $parts = $_ -split '=', 2
        if ($parts.Count -eq 2) { Set-Item "Env:$($parts[0].Trim())" $parts[1].Trim() }
    }
    Write-Host "[env ] 已载入 $Label（不回显内容）" -ForegroundColor DarkGray
    return $true
}

function Test-Port {
    param([int]$Port)
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

# ---------- 1. MySQL ----------
if (Test-Port 3306) {
    Write-Host '[my  ] 3306 已在监听，跳过启动' -ForegroundColor DarkGray
} else {
    Write-Host '[my  ] 3306 未监听，正在启动 MySQL…' -ForegroundColor Yellow
    # 全部用单引号字面量，避免 PowerShell 5.1 下的引号转义问题。
    # 这些路径不含空格，无需再加引号。
    $mysqldArgs = @(
        '--basedir=E:\py\.runtime\mysql\mysql-8.0.25-winx64'
        '--datadir=E:\py\.runtime\mysql\data'
        '--port=3306'
    )
    Start-Process -FilePath (Join-Path $mysqlBin 'mysqld.exe') `
        -ArgumentList $mysqldArgs -WindowStyle Hidden
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        if (Test-Port 3306) { break }
    }
    if (Test-Port 3306) { Write-Host '[my  ] MySQL 已就绪' -ForegroundColor Green }
    else { throw 'MySQL 启动失败：3306 未监听。请检查 E:\py\.runtime\mysql\data 与日志。' }
}

# ---------- 2. 环境变量 ----------
if (-not (Import-EnvFile $credFile 'db-credentials.txt')) {
    throw "缺少数据库凭据文件：$credFile"
}
if (-not $env:CROPDISEASE_DB_PASSWORD) {
    throw '凭据文件里没有 CROPDISEASE_DB_PASSWORD，后端会连不上库。'
}

if ($Key) {
    $env:DEEPSEEK_API_KEY = $Key
    Write-Host '[env ] DEEPSEEK_API_KEY 来自 -Key 参数（不回显）' -ForegroundColor DarkGray
} elseif (-not (Import-EnvFile $keyFile 'deepseek-key.txt')) {
    Write-Host '[warn] 未配置 DEEPSEEK_API_KEY：后端能启动，但智能体会停在 LLM_ERROR。' -ForegroundColor Yellow
    Write-Host "       配置方式：在 $keyFile 写入一行  DEEPSEEK_API_KEY=sk-xxx" -ForegroundColor Yellow
}

# 强制用 JDK 11 —— 不要"尊重"已有的 JAVA_HOME。
# 本机用户级 JAVA_HOME 指向 jdk-21，而 JDK 17+ 下 Lombok 会以
# "cannot access com.sun.tools.javac.processing.JavacProcessingEnvironment" 崩在 testCompile，
# 且 spring-boot:run 会触发 testCompile，所以后端会起不来。
# 之前几次"启动成功"只是因为测试类没变化、testCompile 被跳过，掩盖了这个问题。
if (-not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
    throw "找不到 JDK 11：$javaHome。请确认 E:\py\.runtime 的运行时布局未变。"
}
$env:JAVA_HOME = $javaHome

# 版本校验：让"用错 JDK"这类失败可读，而不是留下一个 Lombok 栈。
# 读 JDK 自带的 release 文件，**不要**调 `java -version`：
# 它把版本写到 stderr，而本脚本设了 $ErrorActionPreference='Stop'，
# 于是 `2>&1` 重定向会被 PowerShell 当成终止性 NativeCommandError 直接崩掉——
# 校验逻辑明明识别出了 JDK 11，却因为读法把自己搞崩（实测踩过）。
$releaseFile = Join-Path $javaHome 'release'
if (-not (Test-Path $releaseFile)) {
    throw "JDK 目录缺少 release 文件，无法校验版本：$releaseFile"
}
$releaseText = Get-Content $releaseFile -Raw
if ($releaseText -notmatch 'JAVA_VERSION="11\.') {
    $found = ([regex]::Match($releaseText, 'JAVA_VERSION="([^"]+)"')).Groups[1].Value
    throw "JDK 版本不符：期望 11.x，实际 $found（路径 $javaHome）"
}
Write-Host "[java] JDK $(([regex]::Match($releaseText, 'JAVA_VERSION="([^"]+)"')).Groups[1].Value)（已强制覆盖 JAVA_HOME）" -ForegroundColor DarkGray

# ---------- 3. 后端 ----------
$bootstrapArg = if ($Full) { '' } else { '--agent.knowledge.bootstrap=false' }
Write-Host "[run ] 启动后端（端口 9999）$bootstrapArg" -ForegroundColor Cyan
Write-Host '       停止：按 Ctrl+C' -ForegroundColor DarkGray

Set-Location $module
$mvnArgs = @('-o', '-s', $settings, 'spring-boot:run')
if ($bootstrapArg) { $mvnArgs += "-Dspring-boot.run.arguments=$bootstrapArg" }
& $maven @mvnArgs
