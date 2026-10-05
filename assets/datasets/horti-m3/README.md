# Horti-M3 在线回放参考数据

此目录包含用于项目演示的标准化观测和已保存的跨年株高校准结果。它们由公开 Horti-M3 数据处理得到，保留补值标记、来源文件摘要及观测校验清单。

- 数据来源：[Zenodo 原始数据记录](https://zenodo.org/records/17217565)
- 数据论文：[Horti-M3 数据论文](https://www.nature.com/articles/s41597-026-07074-w)
- 原数据作者：Yu Gong 等（详细作者信息以原始记录为准）。
- 原数据许可：CC BY 4.0，使用或再分发时请保留原始数据署名和来源。项目标准化、导入与校准处理不改变原始数据的作者归属。
- `normalized/multiyear-observations.json`：2023—2025 环境和生长观测，含处理及估计标记。
- `normalized/multiyear-manifest.json`：观测 SHA256 和导入清单。
- `runs/latest-multiyear.json`：当前跨年校准结果，用于恢复展示。

## 恢复到默认运行目录

在项目根目录执行以下 PowerShell 命令（与当前启动脚本的目录结构一致）：

```powershell
$projectDir = (Get-Location).Path
$workspaceDir = Split-Path -Parent (Split-Path -Parent $projectDir)
$m3TargetDir = Join-Path $workspaceDir '.runtime\datasets\horti-m3'
New-Item -ItemType Directory -Path $m3TargetDir -Force | Out-Null
Copy-Item -LiteralPath '.\assets\datasets\horti-m3\normalized' -Destination $m3TargetDir -Recurse -Force
Copy-Item -LiteralPath '.\assets\datasets\horti-m3\runs' -Destination $m3TargetDir -Recurse -Force
```

若项目目录布局不同，可用 Spring Boot 配置 `agent.m3.data-root` 指向此数据目录的绝对路径。不要在 `normalized` 的 JSON 中修改数值；服务端会校验观测 SHA256。需要重新导入原始压缩包时使用 `scripts/import_horti_m3_multiyear.py`，完整原始包请从上面的公开记录另行下载。

M3 在平台中按历史时间回放，天气和设备响应属于仿真。株高校准评价与在线更新后的拟合偏差分别展示。
