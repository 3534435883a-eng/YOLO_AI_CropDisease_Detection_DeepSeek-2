# 停止本地平台：Vue(8100) / 后端(9999) / Flask(5000) / MySQL(3306)
#
# ！！本文件必须保存为 **带 UTF-8 BOM** 的 UTF-8 ！！
# Windows PowerShell 5.1（双击 .lnk 时用的就是这个解释器）对**无 BOM** 的文件按系统 ANSI
# 代码页（本机为 GBK）解码，文件里的中文会被拆成乱码字节，其中某些字节会撞上引号，
# 导致整个脚本报"字符串缺少终止符"而一行都不执行——这正是"双击启动没反应"的根因。
# 用编辑器改完请确认 BOM 仍在（首三字节 EF BB BF）。
[CmdletBinding()]
param(
    [switch]$KeepMysql
)

# 停止是"尽力而为"：某个服务已经在跑、或已经被手动关掉，都不该让整个流程报错退出。
$ErrorActionPreference = 'Continue'

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

function Wait-PortClosed {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutSeconds = 20
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Test-LocalPort -Port $Port)) {
            return $true
        }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

function Get-PortOwner {
    param([Parameter(Mandatory = $true)][int]$Port)

    $connection = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $connection) {
        return $null
    }
    return Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
}

# 按端口停止，并核对进程名，避免误杀恰好占用同一端口的其它程序。
function Stop-ServiceOnPort {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string[]]$ExpectedNames
    )

    $process = Get-PortOwner -Port $Port
    if (-not $process) {
        Write-Host "  $Label (端口 $Port)：未在运行"
        return
    }
    if ($ExpectedNames -notcontains $process.ProcessName) {
        Write-Host "  $Label (端口 $Port)：占用者是 $($process.ProcessName)（非预期进程），为安全起见未停止" -ForegroundColor Yellow
        return
    }
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    if (Wait-PortClosed -Port $Port -TimeoutSeconds 20) {
        Write-Host "  $Label (端口 $Port)：已停止"
    } else {
        Write-Host "  $Label (端口 $Port)：停止后端口仍被占用" -ForegroundColor Yellow
    }
}

Write-Host '停止本地平台...'

Stop-ServiceOnPort -Port 8100 -Label 'Vue 前端' -ExpectedNames @('node')
Stop-ServiceOnPort -Port 9999 -Label 'Spring Boot 后端' -ExpectedNames @('java')
Stop-ServiceOnPort -Port 5000 -Label 'Flask 识别/向量服务' -ExpectedNames @('python', 'python3', 'pythonw')

# mvn spring-boot:run 的启动器进程会在子 JVM 退出后自行结束；若它变成孤儿进程则一并清理。
$mavenLaunchers = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like '*spring-boot:run*' }
foreach ($launcher in $mavenLaunchers) {
    Stop-Process -Id $launcher.ProcessId -Force -ErrorAction SilentlyContinue
    Write-Host "  Maven 启动器 (PID $($launcher.ProcessId))：已停止"
}

if ($KeepMysql) {
    Write-Host '  MySQL (端口 3306)：按要求保留'
} elseif (Test-LocalPort -Port 3306) {
    # 优先用 mysqladmin 优雅关闭：直接杀进程会让 InnoDB 下次启动走恢复流程。
    $runtimeLink = Join-Path $env:LOCALAPPDATA 'TomatoGreenhouseRuntime'
    $mysqlAdmin = Join-Path $runtimeLink 'mysql\mysql-8.4.11-winx64\bin\mysqladmin.exe'
    $dbUser = [Environment]::GetEnvironmentVariable('CROPDISEASE_DB_USER', 'User')
    if (-not $dbUser) { $dbUser = [Environment]::GetEnvironmentVariable('CROPDISEASE_DB_USER', 'Machine') }
    $dbPassword = [Environment]::GetEnvironmentVariable('CROPDISEASE_DB_PASSWORD', 'User')
    if (-not $dbPassword) { $dbPassword = [Environment]::GetEnvironmentVariable('CROPDISEASE_DB_PASSWORD', 'Machine') }

    $stopped = $false
    if (-not (Test-Path -LiteralPath $mysqlAdmin)) {
        Write-Host "  MySQL：未找到 mysqladmin（$mysqlAdmin）" -ForegroundColor Yellow
    } elseif (-not $dbUser -or -not $dbPassword) {
        Write-Host '  MySQL：环境变量里没有数据库账号（CROPDISEASE_DB_USER / CROPDISEASE_DB_PASSWORD）' -ForegroundColor Yellow
    } else {
        $env:MYSQL_PWD = $dbPassword
        try {
            # 应用账号**没有 SHUTDOWN 权限**（实测报 "Access denied; you need ... the SHUTDOWN
            # privilege(s) for this operation"），所以这里通常会失败并落到强制停止。
            # 想把优雅关闭打通，需要用管理员账号执行一次：
            #   GRANT SHUTDOWN ON *.* TO 'cropdisease_app'@'%';  FLUSH PRIVILEGES;
            $shutdownOutput = & $mysqlAdmin --protocol=TCP -h 127.0.0.1 -P 3306 -u $dbUser shutdown 2>&1
            if ($LASTEXITCODE -eq 0) {
                $stopped = Wait-PortClosed -Port 3306 -TimeoutSeconds 30
            } else {
                Write-Host "  MySQL：mysqladmin shutdown 未成功（$($shutdownOutput -join ' ')）" -ForegroundColor Yellow
            }
        } finally {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        }
    }
    if ($stopped) {
        Write-Host '  MySQL (端口 3306)：已优雅关闭'
    } else {
        # 强制停止对 InnoDB 是安全的：下次启动走崩溃恢复，实测数据无损（338 块知识块与检索均正常）。
        Write-Host '  MySQL：改用强制停止（下次启动会走 InnoDB 恢复流程，实测数据无损）'
        Stop-ServiceOnPort -Port 3306 -Label 'MySQL' -ExpectedNames @('mysqld')
    }
} else {
    Write-Host '  MySQL (端口 3306)：未在运行'
}

Write-Host ''
Write-Host '平台已停止。双击"启动农业智能体平台"可重新启动。' -ForegroundColor Green
Start-Sleep -Seconds 2
