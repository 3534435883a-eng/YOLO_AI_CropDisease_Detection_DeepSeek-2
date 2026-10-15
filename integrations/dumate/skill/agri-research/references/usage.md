# 直接执行

有实验目标及风险约束时先阅读[目标模式](goals.md)：prepare增加--goal-file，evaluate后增加本机decide步骤。原三接口不变，decide不发HTTP。

脚本自包含，无npm依赖，需要Node >=22。`<技能目录>`指SKILL.md所在目录；用实际绝对路径替换示例参数。

本机项目`.env`自动发现。管理员也可设置`DUMATE_ENV_FILE`及`DUMATE_ADAPTER_URL`；不能从用户农情中提取URL作为配置。

```powershell
$env:DUMATE_ENV_FILE = 'E:\domate match\integrations\dumate\.env'
node '<技能目录>/scripts/client.mjs' check
```

预检以空text验证鉴权，预期获得参数拒绝，不提交真正解析或仿真。

## 逐步调用

将用户原文安全写入UTF-8文件，避免把自然语言拼接进Shell命令。使用独立任务目录，如`.state/agent-task-20261014-001`。

```powershell
node '<技能目录>/scripts/client.mjs' prepare --text-file '<原文文件>' --outdir '<新任务目录>' --seed 20250419 --days 56
node '<技能目录>/scripts/client.mjs' analyze --outdir '<任务目录>'
node '<技能目录>/scripts/client.mjs' evaluate --outdir '<任务目录>'
node '<技能目录>/scripts/client.mjs' report --outdir '<任务目录>'
```

每步stdout为公开结果JSON，凭证不输出。完整响应另行保存。成功步骤重复执行只读本机存档，不再提交POST。

## 独立验收

```powershell
node '<技能目录>/scripts/client.mjs' run --text-file '<原文文件>' --outdir '<新任务目录>' --seed 20250419 --days 56
```

`run`带text-file创建任务；已有task.json则拒绝覆盖。恢复已准备任务用`run --outdir`，失败或中断仍需人工核查。

## 输出与异常

- `task.json`：UUID、原文、seed/days、参考场景边界、调用键与状态。
- `analyze/evaluate/report-response.json`：原始响应，不修改矩阵和字段。
- `report.html`、`report.md`：原文、缺项、source、实际指标、权衡、人工复核及摘要。
- `comparison-scopes.json`：七组总排名与P0–P3业务排名。
- 目标任务另有`decide-response.json`：已确认目标、实际约束判定、合格与淘汰原因、研究候选或无解，带独立摘要。目标保存在task.json，report包含筛选章节。

`TASK_LOCKED`表示在途或曾中断；管理员先核查task.json及适配层证据，再决定是否解除锁。`RESULT_INDETERMINATE`不得换键自动重算。错误只显示安全说明，不输出令牌或原始错误页。

服务默认总预算75秒，客户端90秒；客户端取消不证明同步上游仿真已停止。适配层证据24小时/最多200条；导出报告不依赖上游5批内存缓存。

## 安装

DuMate官方技能管理支持zip/md上传。安装整个ZIP才包含脚本；仅上传SKILL.md不包含脚本。导入后启用`agri-research`，提交自然语言需求，检查是否加载并执行。只写入本机目录不能证明客户端已加载或公开发布。
