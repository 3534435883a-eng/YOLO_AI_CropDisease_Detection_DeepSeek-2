[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$taskModuleRoot = Split-Path -Parent $PSScriptRoot
$taskEnvFile = Join-Path $taskModuleRoot '.env'
$taskStateRoot = Join-Path $taskModuleRoot '.state'
$taskNode = (Get-Command node.exe -ErrorAction Stop).Source
function Test-AdapterPort([int]$Port) {
    $taskClient = New-Object Net.Sockets.TcpClient
    try {
        $taskPending = $taskClient.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $taskPending.AsyncWaitHandle.WaitOne(300)) { return $false }
        $taskClient.EndConnect($taskPending)
        return $true
    } catch { return $false } finally { $taskClient.Close() }
}
if (-not (Test-AdapterPort 9999)) { throw 'Agricultural backend 127.0.0.1:9999 is unavailable. Start the existing backend first.' }
New-Item -ItemType Directory -Path $taskStateRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $taskEnvFile)) {
    if (Test-AdapterPort 9998) { throw 'Port 9998 is occupied but its credential is not configured. Preserve the service and configure .env first.' }
    $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
    $taskBytes = New-Object byte[] 32
    $taskRandom.GetBytes($taskBytes)
    $taskRandom.Dispose()
    $taskToken = [Convert]::ToBase64String($taskBytes)
    [IO.File]::WriteAllText($taskEnvFile, "DUMATE_TOKEN=$taskToken`nDUMATE_UPSTREAM=http://127.0.0.1:9999`nDUMATE_PORT=9998`n", [Text.UTF8Encoding]::new($false))
}
if (-not (Test-AdapterPort 9998)) {
    Start-Process -FilePath $taskNode -ArgumentList @('--env-file=.env', 'src/server.mjs') -WorkingDirectory $taskModuleRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskStateRoot 'adapter.log') -RedirectStandardError (Join-Path $taskStateRoot 'adapter.err.log') | Out-Null
    $taskDeadline = [DateTime]::UtcNow.AddSeconds(10)
    while (-not (Test-AdapterPort 9998) -and [DateTime]::UtcNow -lt $taskDeadline) { Start-Sleep -Milliseconds 250 }
}
$env:DUMATE_ENV_FILE = $taskEnvFile
& $taskNode (Join-Path $taskModuleRoot 'skill\agri-research\scripts\client.mjs') check
if ($LASTEXITCODE -ne 0) { throw 'Adapter preflight failed; existing services and credentials were preserved.' }
