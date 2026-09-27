<#
  停止本机智能体 —— 2026-09-27

  ⚠ 为什么按**端口**找 PID，而不是按进程名：
  `spring-boot:run` 会 fork 一个独立 JVM；只杀 maven，9999 仍被占着，下次启动会以为还活着。
  同理 MySQL 是 mysqld.exe、Flask 是 python.exe，按名字杀会误伤别的项目。

  用法：scripts\stop-local-agent.cmd
        （或 powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-local-agent.ps1）
#>
$ErrorActionPreference = 'Stop'

foreach ($port in @(9999, 5000, 3306)) {
    $pids = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
              Select-Object -ExpandProperty OwningProcess -Unique)
    if ($pids.Count -eq 0) {
        Write-Host ("[{0,-5}] 未在监听" -f $port) -ForegroundColor DarkGray
        continue
    }
    foreach ($processId in $pids) {
        try {
            $name = (Get-Process -Id $processId -ErrorAction Stop).ProcessName
            Stop-Process -Id $processId -Force -ErrorAction Stop
            Write-Host ("[{0,-5}] 已停止 {1}（PID {2}）" -f $port, $name, $processId) -ForegroundColor Green
        } catch {
            Write-Host ("[{0,-5}] 停止 PID {1} 失败：{2}" -f $port, $processId, $_.Exception.Message) -ForegroundColor Yellow
        }
    }
}

Start-Sleep -Seconds 2
$still = @(9999, 5000, 3306) | Where-Object {
    Get-NetTCPConnection -State Listen -LocalPort $_ -ErrorAction SilentlyContinue
}
if ($still.Count -eq 0) {
    Write-Host ''
    Write-Host '三个端口都已释放。' -ForegroundColor Green
} else {
    Write-Host ("仍在监听：{0}（可能需要管理员权限）" -f ($still -join ', ')) -ForegroundColor Yellow
}
