# 实验目标理解与多指标决策设计及实施计划

**Goal:** 在已授权的独立功能分支新增目标驱动实验模式，复用三接口和仿真引擎，给DuMate可复算的约束筛选依据。

**Architecture:** DuMate理解自然语言并澄清指标、基准和阈值；客户端prepare时保存已确认的结构化目标与摘要。evaluate仍只发送原有参数。新增本机decide步骤仅读取有摘要与关联编号的真实评测，按硬约束过滤P0–P3，按明确优先顺序排序；report保留原API并附加本机决策结果。

**Tech Stack:** Node >=22，内置crypto/fs/node:test，零新依赖。

**Spec:** 用户本轮六步设计；已有task_plan.md全局范围。用户已授权实施，不另设审批或重构。

## 契约与边界

- goalText为原始目标文本，confirmed=true表示DuMate已向用户澄清并获得明确条件；此标志不是外部授权证明。
- baseline必须显式选择P0–P3之一；objectives以数组顺序表示优先级，各指标方向固定，不使用模型猜出的权重。
- constraints必须明确非负指标的允许相对增幅；病害积分和最终严重度是独立指标，“不能显著增加”不能偷偷变成任意百分比。
- minWaterSavingPct显式指定0–100；默认不替用户填写。相对阈值均使用基准绝对值计算，零基准不进行除零。
- 固定P0–P3用于管理方案；P4–P6仅消融展示。仿真约束违规必须为0；目标筛选失败计数与此字段分别展示。
- NO_FEASIBLE表示无合格方案；NO_IMPROVEMENT表示仅基线或无目标改善；RECOMMENDED表示满足用户约束并优于基线。并列不任意选单个。
- 只有四个已有管理策略的有限集合排序，没有新模型、连续参数寻优、实棚诊断或效益验证。
- 目标冻结于评测之前；更改目标须新任务。报告校验决策摘要并重新计算以防文件改写；原始HTTP响应保持不变。

## 最小修改文件

- 新增skill/agri-research/scripts/decision.mjs：目标Schema、七组矩阵校验、确定性筛选。
- 修改skill/agri-research/scripts/client.mjs：prepare --goal-file、decide、关联验证、run中的顺序和报告附件。
- 修改skill/agri-research/scripts/report.mjs：目标、阈值、逐项合格/淘汰原因、状态和本机决策摘要。
- 新增test/decision.test.mjs；扩展客户端关联与故障验证。
- 修改SKILL.md、references/usage.md；新增references/goals.md及两份明确阈值的示例JSON。
- 修改scripts/package-release.mjs，确保Skill包含全部脚本和目标说明；更新参赛简介、提示词、讲稿与应用方案。
- 新增docs/github-related-projects.md：官方仓库来源、许可证和借鉴范围；不复制第三方实现。

## 执行检查点

- [x] 使用真实已存JSON编写筛选行为测试：可推荐、全不合格、只保留基准、边界值、零基准、并列、错误输入和缺失指标。
- [x] 实现目标校验和排序，验证原始矩阵不变、消融组永不进入候选集。
- [x] 客户端贯通prepare → analyze → evaluate → decide → report；旧三步任务兼容。
- [x] 真实服务执行两组新任务，并核对原始响应、决策与报告。
- [x] 更新演示提示词及材料；打包解压验证脚本完整，摘要与私有文件排除。
- [x] 全部相关测试通过，推送原功能分支并更新草稿PR，不合并main。
