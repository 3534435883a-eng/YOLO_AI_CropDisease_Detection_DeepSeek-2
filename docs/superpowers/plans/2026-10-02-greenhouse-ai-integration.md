# 大棚与决策助手统一改进 Implementation Plan

> For agentic workers: 本轮在当前会话逐项实施，按批准设计执行。开发者约束优先：不新增或运行自动测试，不提交Git，不再询问实施方式。

**Goal:** 同一M3运行中展示随机事件、AI处置和环境反馈，并改进日常回答、回答界面及有出处的知识覆盖。

**Architecture:** M3参考计算保持因果时序，独立ScenarioSession维护模拟环境、设备、事件和未干预对照；异步AI服务产生结构化方案，统一校验后应用。聊天绑定服务端运行上下文，通过同一仿真服务读状态与请求决策。Vue复用回答正文与事件面板，所有数据来源保留类型与条件。

**Tech Stack:** Java 8 / Spring Boot / Jackson / 既有DeepSeek服务，Vue 3 / TypeScript / Element Plus / Three.js / ECharts，无新增框架。

**Spec:** ../specs/2026-10-02-greenhouse-events-ai-design.md。用户2026-10-02回复“可以”，本方案已批准。

## Global Constraints

- M3棚内记录不能作为室外实测；随机天气、风雨和设备响应为模拟。
- 先预测，观测到达再修正；事件场景不计算M3独立精度。
- 单运行设备动作串行、校验词表/健康/互锁/版本，AI错误如实显示。
- 自然回答与结构化动作分开；引用和实时状态必须来自实际读取。
- 新知识摘要保留机构/作者、单位、条件、版本、URL，不复制全文。
- 暖白农业绿桌面界面；原始工具过程折叠，正文优先。
- 不新增或运行自动测试。完成生产构建，并执行此前用户授权的实际模拟及不同话术请求。

## Task 1: 模拟环境与设备状态

Create: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/m3/scenario/ScenarioSession.java`。

Interfaces:
```java
ObjectNode advance(JsonNode referenceFrame);
ObjectNode snapshot();
void configure(boolean autoEvents, boolean autoActuation);
void trigger(String type);
void endWeather();
void repair(String device);
ObjectNode applyPlan(JsonNode plan, long expectedVersion, String requestId, boolean apply);
```
- [x] 保存种子、事件时钟、版本、设备出力/健康、风险累计、独立环境状态及影子对照。
- [x] 以M3环境变化推动基线；状态化温度/水汽响应、照明/CO₂/通风关联，异常渐入渐出，记录假设和参数。
- [x] 实现七类事件、数量限制、冷却时间、设备健康及动作互锁，动作幂等和有效期。

## Task 2: 异步AI与M3接入

Create: `m3/scenario/ScenarioAiService.java`。Modify: `m3/M3LiveService.java`, `m3/M3LiveController.java`。

Interfaces:
```java
void requestDecision(ScenarioSession session, String question, boolean apply);
ObjectNode scenarioCommand(String id, String operation, JsonNode input);
```
- [x] 既有DeepSeek代理输出理由、动作、持续时间和复查条件；保持一个在途请求和事件预算，失败记为失败。
- [x] 运行保存ScenarioSession，在帧中追加scenario；pending期间不消费新M3时段，结束/删除使在途方案失效。
- [x] 增加配置、手动触发/结束天气、故障恢复与AI决策接口；快照提供实时决策状态。

## Task 3: 聊天绑定与自然回答

Create: `agent/tool/M3SimulationSnapshotTool.java`, `agent/tool/M3SimulationDecisionTool.java`。
Modify: `agent/dto/AgentChatRequest.java`, `agent/controller/AgentChatController.java`, `agent/orchestrator/AgentOrchestrator.java`, `DeepSeekLlmClient.java`, `agent/guard/GuardrailService.java`。

```java
AgentResult run(String sessionId, String question, String crop,
                String simulationRunId, Long legacyRunId, Consumer<AgentStepEvent> sink);
```
- [x] 服务端绑定simulationRunId并注入工具入参，初始快照及时读取；模型不能自行切换运行。
- [x] 移除关键词/固定横幅前提，允许不检索的一般回答；相关资料有则引用，无则说明具体限制。
- [x] 所有成功分支保存历史，修正错误性质与作用域声明；只显示已用有效引用。
- [x] 结构化仿真决策与回答正文分离，显式执行请求才应用；普通建议不会改设备。

## Task 4: 有出处知识条目

Create: `src/main/resources/knowledge/guidance-tomato-weather.json`, `papers-tomato-feedback.json`（后端路径）。Modify: `CitedKnowledgeReader.java`及需要增强的现有来源。

```json
{"source":{"sourceCode":"...","sourceName":"...","sourceType":"B","sourceUrl":"..."},"entries":[{"id":"...","topic":"...","fieldType":"ENVIRONMENT","text":"条件与机制的摘要"}]}
```
- [x] 天气资料按不同原来源登记；正文保留地域/棚型/日期。已有结露、阴雨与水肥条目不重复建来源。
- [x] 增加温湿机制和反馈/同化论文摘要，区分环境提示和病害诊断。
- [x] 注册清单，构建后按来源分组reingest，再读取实际块/向量/来源状态。

## Task 5: 共享正文与聊天UI

Create: `Vue/src/components/agent/AnswerBody.vue`。Modify: `Vue/src/api/agent/chat.ts`, `Vue/src/views/agentChat/index.vue`。

```ts
type Props = { text: string; citations?: unknown[] };
// 元数据与正文独立，引用事件携带当前回复的编号。
```
- [x] 安全节点解析段落/标题/列表/强调/表格/引用，不注入任意HTML；限制链接协议。
- [x] 正文优先、过程折叠、逐回复来源、关联运行和动作状态；复制/停止/重试可用。
- [x] 会话显示一般回答、引用回答、检索降级和模型失败的准确状态。

## Task 6: 大棚控制面板与动画

Create: `Vue/src/views/digitalTwin/components/ScenarioPanel.vue`, `Vue/src/views/digitalTwin/three/weather.ts`。
Modify: `Vue/src/api/m3/live.ts`, `M3LiveWorkbench.vue`, `digitalTwin/index.vue`, `digitalTwin/three/scene.ts`（以实际Three.js目录为准）。

```ts
interface ScenarioView { version:number; pending:boolean; environment:Record<string,number>; devices:Record<string,boolean>; }
```
- [x] 同一帧环境/设备/时钟驱动场景与面板，默认半小时步进，AI期间轮询状态。
- [x] 自动事件/自动处置/手动触发、事件与动作时间线、观察结果/对照趋势；助手入口带同一UUID。
- [x] 室外批量雨粒、风与光照云雾示意，按画质缩减粒子并释放资源，尊重暂停/降低动态偏好。

## Task 7: 构建、实际业务展示与交付

- [x] Maven生产构建使用 `-Dmaven.test.skip=true`；Vite生产构建；修复构建错误。
- [x] 重启本地服务保留实际配置，知识入库并读取索引状态。
- [x] 执行用户授权的大棚天气与AI业务流程，以及一般解释/当前状态/追问/执行等话术；在浏览器查看UI与动画。
- [x] 记录真实模型返回、环境反馈、失败和未解决状态；更新研究和进度文件，交付入口及截图。

## Self-review

本计划覆盖批准设计的模拟、随机事件、AI处置、聊天、自然回答、UI、论文/知识、三维动画及交付。Task 1–3共享ScenarioSession与run UUID；Task 5–6复用回答组件和scenario快照。自动测试和Git提交步骤按开发者约束省略。

## 交付记录

2026-10-02：上述实施已完成，实际业务与限制见[实施结果](../../research/2026-10-02-greenhouse-ai-integration-results.md)。知识库通过构建后的自动引导服务按来源重新入库并载入索引；非人工调用重复导入。后端和前端生产构建成功；没有新增或运行自动测试。浏览器已保留助手及大棚展示标签页。
