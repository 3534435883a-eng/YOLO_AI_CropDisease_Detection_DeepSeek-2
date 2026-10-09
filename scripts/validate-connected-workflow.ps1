[CmdletBinding()]
param([switch]$LiveModel)
$ErrorActionPreference='Stop'
$base='http://127.0.0.1:9999'
$taskId='880bb12c-444e-4180-83e2-8c52e88bdf80'
$runId='0d01c93d-9794-4dc4-b875-073778913055'
$output=Join-Path $PSScriptRoot '../docs/validation/2026-10-09'
New-Item -ItemType Directory -Force -Path $output|Out-Null
$checks=New-Object System.Collections.Generic.List[object]
$completed=$false
function Check($name,$condition){if(-not $condition){throw "验证未通过：$name"};$checks.Add([pscustomobject]@{name=$name;passed=$true});Write-Output "PASS $name"}
function Api($path,$body=$null,$method='GET'){
 $args=@{Uri=$base+$path;Method=$method;TimeoutSec=30}
 if($null -ne $body){$args.Body=[Text.Encoding]::UTF8.GetBytes(($body|ConvertTo-Json -Depth 35 -Compress));$args.ContentType='application/json;charset=UTF-8'}
 $reply=Invoke-RestMethod @args
 if($reply.code -ne '0'){throw "$path : $($reply.msg)"};return $reply.data
}
function Stream($path,$body,$filename){
 $reply=Invoke-WebRequest -Uri ($base+$path) -Method Post -Body ([Text.Encoding]::UTF8.GetBytes(($body|ConvertTo-Json -Depth 35 -Compress))) -ContentType 'application/json;charset=UTF-8' -TimeoutSec 260
 $reply.Content|Set-Content -LiteralPath (Join-Path $output $filename) -Encoding utf8
 $events=@(foreach($frame in [regex]::Split($reply.Content,"\r?\n\r?\n")){
  $data=@($frame -split "\r?\n"|Where-Object{$_ -match '^data:'}|ForEach-Object{$_.Substring(5)}) -join "`n"
  if($data){$item=$data|ConvertFrom-Json -Depth 65;if($frame -match 'event:error'){throw $item.message};$item}
 })
 return $events
}
try{
 $task=Api "/agent/tasks/$taskId"
 $run=Api "/m3/live/$runId`?compact=true"
 Check '原任务可恢复且与同一运行关联' ($task.simulationRunId -eq $run.runId)
 Check '暂停后模拟游标保留' ($run.cursor -ge 10 -and -not $run.playback.playing)
 Check '文字、图片、CSV三类证据保留' (@($task.evidence).Count -ge 3 -and @($task.evidence|Where-Object type -eq 'IMAGE').Count -gt 0)
 Check '人工复查和虚拟设备回执分别保留' (@($task.observations).Count -gt 0 -and @($task.actions|Where-Object type -eq 'SIMULATION').Count -gt 0)
 if($LiveModel){
  $requestId=[guid]::NewGuid().ToString()
  $question='本任务番茄连续阴雨、叶片有斑，已上传图片和农情表格。请解释本任务实际图像候选，并结合最新复查给出今天的管理建议，最多4点；区分用户记录与模拟状态，只分析，不执行设备。'
  $events=Stream '/ai/agent/chat' @{taskId=$taskId;simulationRunId=$runId;requestId=$requestId;sessionId="flow-validation:$taskId";crop='番茄';question=$question;allowSimulationActions=$false} 'assistant.sse'
  $final=@($events|Where-Object type -eq 'final')[-1]
  Check '真实助手完成回答并正确识别候选' ($final.data.status -eq 'DONE' -and $final.message -match '早疫' -and $final.message -notmatch '类别.{0,20}与作物.{0,10}不匹配')
  $task=Api "/agent/tasks/$taskId"
  $saved=@($task.turns|Where-Object {$_.requestId -eq $requestId -and $_.role -eq 'ASSISTANT'})
  Check '真实回答全文及冻结上下文完整归档' ($saved.Count -eq 1 -and $saved[0].content -eq $final.message -and $saved[0].status -eq 'DONE')
  $csv=@($task.evidence|Where-Object type -eq 'CSV')[-1]
  $planRequest=[guid]::NewGuid().ToString()
  $events=Stream '/ai/agri/plan/deduce' @{taskId=$taskId;simulationRunId=$runId;requestId=$planRequest;situation=$csv.details.input;days=3;question='结合本任务图像候选、农情CSV和复查，制定未来3天管理清单，分别说明人工事项、设备建议、作用代价和复查条件，用简短表格，不执行设备，不承诺病斑治愈。'} 'plan.sse'
  $plan=@($events|Where-Object {$_.type -eq 'final' -or $_.markdown})[-1]
  if(-not $plan.markdown){$plan=$plan.data}
  Check '关联规划完成且不采用旧固定基线' ($plan.markdown.Length -gt 80 -and -not $plan.baseline.available)
  $structured=if($plan.structured -is [string]){$plan.structured|ConvertFrom-Json}else{$plan.structured}
  $outsideDays=@($structured.schedule|Where-Object {$_.dayOffset -lt 0 -or $_.dayOffset -ge 3 -or $_.dayOffset -ne [int]$_.dayOffset})
  Check '规划排程限定在请求的三天内' ($structured -and @($structured.schedule).Count -gt 0 -and $outsideDays.Count -eq 0)
  Check '规划不把回放与仿真误称实测矛盾或病原感染线' ($plan.markdown -notmatch '三套数不能同时为真|三组.{0,8}不能同时为真|湿润超过.{0,8}小时.{0,30}(就会|必然).{0,8}(病斑|发病)')
  $task=Api "/agent/tasks/$taskId"
  Check '规划保存到同任务并产生人工事项' (@($task.turns|Where-Object {$_.requestId -eq $planRequest -and $_.status -eq 'DONE'}).Count -eq 1 -and @($task.actions|Where-Object {$_.requestId -eq $planRequest -and $_.type -eq 'HUMAN'}).Count -gt 0)
 }
 $report=Invoke-WebRequest -Uri "$base/agent/tasks/$taskId/report" -TimeoutSec 30
 Check '报告包含证据、助手、复查与完整参数指纹' ($report.Content -match '图像识别为候选' -and $report.Content -match '流程验证记录' -and $report.Content -match 'SHA-256' -and $report.Content -match '助手建议')
 $report.Content|Set-Content -LiteralPath (Join-Path $output '农情任务报告.md') -Encoding utf8
 $task|ConvertTo-Json -Depth 80|Set-Content -LiteralPath (Join-Path $output 'task-archive.json') -Encoding utf8
 $run|ConvertTo-Json -Depth 80|Set-Content -LiteralPath (Join-Path $output 'paused-run.json') -Encoding utf8
 $completed=$true
}finally{
 [ordered]@{verifiedAt=(Get-Date).ToString('o');completed=$completed;liveModel=[bool]$LiveModel;checks=@($checks.ToArray())}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $output 'workflow-results.json') -Encoding utf8
 Copy-Item -LiteralPath (Join-Path $output 'workflow-results.json') -Destination (Join-Path $output $(if($LiveModel){'workflow-live-results.json'}else{'workflow-local-results.json'}))
}
