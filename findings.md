# 番茄温室智能体实施发现

## 已有能力与边界

- 项目由 Vue 3、Spring Boot 2.3、MySQL 和 Flask/YOLO 组成；后端启动类为 `com.example.Ece.Ece`。
- 原有环境页和首页存在静态展示，因此新增模拟运行摘要成为唯一共享环境状态来源。
- 旧 `greenhouse` 数据以 `id=77` 的 8 号温室为番茄基线；其名称不能当作唯一键。
- 旧 `storage` 是设备物料，不可把灌溉或 CO2 模拟消耗写入该表。

## 本次新增实现

- 已创建 create-only 的 `database/migrations/V20260921_01__agent_simulation.sql`，包含运行、快照、设备、决策、动作、资源库存/流水、视觉事件、告警、审计及参数来源的隔离表。
- 纯 Java 推演层已有确定性番茄温室状态、决策策略和单元测试。
- 前端已有 `/agentCenter`、`agentRun` Pinia store、后端 API 封装、全局运行指示器，并已改造环境页和数据大屏。

## 接口契约

- 所有新接口经 `/api/agent` 暴露，沿用 `Result<?>` 包装。
- 指挥中心依赖 `run`、`currentState`、`metrics`、`devices`、`alerts`、`resources`、`strategySummary` 和比较项 `items`。
- 当前前端已定义创建、开始、暂停、单步、重置、运行摘要、比较和手动设备接管接口。

## 验证重点

- 运行同一初始状态两次须得到完全一致的推演和决策结果。
- 通风与 CO2 必须互斥；离线、故障、锁定、冷却和资源不足应持久化为阻断动作/告警而非伪造成功。
- 视觉导入应保留证据，默认 `PENDING_REVIEW`，只产生审核风险。

## 验证结果

- `mvn test`：18 tests, 0 failures, 0 errors。
- `vite build`：production build succeeded；现有 Sass、字体和旧背景资源警告仍属于项目既有构建提示。
- 独立迁移静态检查：未发现 `DROP TABLE`、修改旧 `greenhouse` 或旧表外键语句。
