# 番茄温室智能体运行说明

## 定位

本模块是“番茄温室环境-病害风险-调控策略仿真与可解释决策系统”。一期只覆盖一个番茄温室和五类虚拟设备：灌溉、通风、补光、遮阳、CO2 补给。输出是可复算的模拟结果，不代表现场传感器实测，也不连接真实执行器。

## 部署顺序

1. 备份 `cropdisease` 数据库。
2. 在目标数据库执行 `database/migrations/V20260921_01__agent_simulation.sql`。该文件只创建 `agent_*` InnoDB 表，不应重新导入 `cropdisease.sql`。
3. 配置 Spring Boot 的 `CROPDISEASE_DB_USER` 和 `CROPDISEASE_DB_PASSWORD`。
4. 启动后端（默认 `9999`）和 Vue 前端（开发代理默认转发到 `9999`）。
5. 打开“智能体指挥中心”，创建 8 号温室番茄模拟。

## 运行与来源

- 一个虚拟步长为 15 分钟，默认运行 24 小时（96 步）。
- 初始状态读取旧 `greenhouse` 的历史记录；找不到时使用明确的默认场景参数。
- 快照来源标记为 `LEGACY_HISTORY` 或 `SIMULATED`；视觉导入标记为 `VISION_SIGNAL` 且状态固定为 `PENDING_REVIEW`。
- 水、CO2、能源属于本次运行的独立虚拟库存，不扣减旧 `storage` 中的设备记录。
- 同一基线、种子和模型版本会产生相同的状态与规则结果。

## 关键接口

```text
GET  /agent/runs/active
POST /agent/runs
GET  /agent/runs/{id}/summary
GET  /agent/runs/{id}/comparison
POST /agent/runs/{id}/start|pause|step|reset|replay
POST /agent/runs/{id}/devices/{code}/manual
POST /agent/runs/{id}/devices/{code}/health
POST /agent/runs/{id}/vision-events
POST /agent/runs/{id}/explanation
```

每个仿真步在一个事务中写入环境快照、规则决策、动作、资源流水、告警和审计记录。人工接管、设备离线/故障、资源不足或通风与 CO2 冲突会生成阻断动作，不会伪造成功。

## 当前限制

YOLO 标签和置信度尚未完成校准，视觉记录只能触发人工复核提示，不能触发农药或自动处置。灭火、紧急联系人、MQTT/串口和三维温室模型保留为后续适配层。参赛材料应将效果表述为场景推演和基线对比，待补充 7 至 14 天人工或传感器记录后再做参数校准。
