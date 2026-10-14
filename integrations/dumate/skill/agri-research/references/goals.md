# 实验目标理解与多指标决策模式

当用户提出“节水优先”“病害不能显著增加”“找到满足条件的方案”等目标时使用。DuMate负责把研究问题整理为结构化目标，脚本负责有限策略集合的指标判断；脚本不调用LLM。

## 澄清与确认

向用户核对：比较基准是哪种策略；使用病害压力积分、最终严重度还是两者；每项指标允许增加百分比；最低节水幅度；模拟天数（1–56）；目标优先顺序。已有明确值不重复问；缺少阈值不能自行填“5%/10%”并假装得到用户同意。可提出56天、seed20250419和P2基准作为建议，说明假设后取得确认。用户可以直接给全套条件。

例如“病害压力不能显著增加”仍需澄清积分/终值及百分比。没有确认时展示待确认计划，不调用evaluate、不生成推荐。

## 目标JSON

保存为UTF-8文件，完整键如下。仅允许这些键。

```json
{
  "goalText": "以P2为基准，至少节水10%，病害压力积分不增加，优先最少用水。",
  "confirmed": true,
  "baseline": "P2_RULE_ENGINE",
  "minWaterSavingPct": 10,
  "objectives": [{"metric":"waterUsedM3","direction":"min"}],
  "constraints": [{"metric":"diseasePressureIntegral","maxIncreasePct":0}]
}
```

confirmed记录对话中的条件确认，不能伪造、也不表示第三方签名。完整原始目标放goalText。农情原文另用text-file；没有农情时先向用户说明只准备参考实验，取得必要的番茄研究场景描述，不编造真实观测。

baseline只允许P0_NONE、P1_FIXED_MANUAL、P2_RULE_ENGINE、P3_AGENT。百分比0–100，原始值比较不四舍五入。P4–P6永不进入管理方案推荐。

objectives最多3项，数组顺序为用户优先级；相同第一目标时比较第二目标，以此类推；所有目标并列则保留并列方案，不编造加权总分。min方向可用waterUsedM3、energyKWh、costYuan、diseasePressureIntegral、finalSeverityTotal、highTemperatureMinutes、highHumidityMinutes；max方向可用profitYuan、marketableYieldKg。

constraints为1–7项，只允许上述min方向指标，每项给maxIncreasePct，允许上限=基准值×(1+百分比/100)。最低节水要求转换为waterUsedM3≤基准用水×(1-minWaterSavingPct/100)。仿真constraintViolations必须为0。上限0不能靠百分比允许正值；基准用水0时正百分比节水目标会被拒绝，不能除零。

## 执行顺序

```powershell
node '<技能目录>/scripts/client.mjs' prepare --text-file '<农情UTF8文件>' --goal-file '<已确认目标JSON>' --outdir '<新独立目录>' --seed 20250419 --days 56
node '<技能目录>/scripts/client.mjs' analyze --outdir '<任务目录>'
node '<技能目录>/scripts/client.mjs' evaluate --outdir '<任务目录>'
node '<技能目录>/scripts/client.mjs' decide --outdir '<任务目录>'
node '<技能目录>/scripts/client.mjs' report --outdir '<任务目录>'
```

目标冻结在task.json的experimentPlan和experimentPlanSha256中。修改目标应新建任务。decide不使用凭证、不发HTTP，仅读取经摘要和关联验证的真实响应，新增decide-response.json。report上游请求仍仅包含analysisId/evaluationId，目标筛选是客户端附加章节。普通无目标任务仍用原三步流程。

## 结果解释

- RECOMMENDED：符合全部条件且按用户优先级优于基准；仅称模型内研究候选。
- NO_FEASIBLE：无合格方案，如实说明，展示每条淘汰原因。
- NO_IMPROVEMENT：有合格方案但没有优于基准的候选；不推荐新方案。

病害积分约束不保证最终严重度，最终严重度约束也不代替积分；能耗、利润和产量未约束时不提供保证。P0即使零用水也有成本、零产量可能不可取。所有策略模拟利润仍可能为负。不得为得出推荐静默放宽条件。

示例goal-integral.json仅约束积分；goal-strict.json同时约束积分和最终严重度。两者是明确的演示条件，不能冒充用户对模糊目标的确认。该模式只筛选四个已有策略，没有设计新的控制动作或改变参考场景；没有实棚验证。
