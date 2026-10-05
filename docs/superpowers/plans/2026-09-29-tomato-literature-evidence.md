# 番茄论文参考接入 Implementation Plan

**Goal:** 将已核对论文转为可引用摘要，并形成模型参数与 M3 对比清单。

**Architecture:** 六份 JSON 来源资源进入现有 CitedKnowledgeReader，由 KnowledgeBootstrap 按来源写入索引。研究文档记录参数和观测对比规则。

**Tech Stack:** Java 8、Spring Boot、既有 JSON 知识清单、Markdown。

**Spec:** docs/superpowers/specs/2026-09-29-tomato-literature-evidence-design.md

## Global Constraints

- 本轮直接在当前会话继续已授权工作。
- 不新增或运行测试；构建使用 maven.test.skip=true。
- 摘要保留条件与边界，不修改默认模型数值，不伪造观测。
- 六篇独立来源，唯一 sourceCode/sourceTable/id；sourceType=B，authorityLevel=2。

## 任务

- [x] 在 src/main/resources/knowledge 新增六份 papers-tomato-*.json，按既有 source/entries 契约整理温室控制、补光、覆盖、间作与营养摘要。
- [x] 向 CitedKnowledgeReader.RESOURCES 登记六个精确文件路径；没有来源时沿用既有错误处理。
- [x] 保存参数证据清单，标明默认值、文献候选和校准前提；更新 TomatoGrowthParameters 的出处说明，保留数值。
- [x] 保存 M3 观测字段、同批匹配、量纲转换、原始/插值数据区分、训练/保留验证与误差公式。
- [x] 编译生产代码并使运行资源生效，确认实际启动与入库状态；无需全量 reingest 既有来源。
- [x] 更新进度、接入清单和用户说明。
