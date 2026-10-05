# Horti-M3 全平台推演适配实施计划

> **For agentic workers:** 依照本计划逐项实施并保留已有工作区修改。由于当前任务明确未要求测试或验证，不新增、修改或运行测试；完成代码后做源码静态核对与前后端构建；页面只查看展示，不执行推演测试。

**Goal:** 让农业平台的默认推演、报告、离线示例与数字孪生共用 Horti-M3 2025/CK/广辉201 场景定义和 30 分钟环境步长。

**Architecture:** 在 Spring Boot 中建立不可变的 M3 场景配置，服务于仿真默认值、API 元数据、面积口径和报告；Vue 侧用同一发布参数渲染棚体与场景说明。模拟数据和 M3 公开观测保持不同来源；论文没有给出的模型与设备参数继续标成未校准假设。

**Tech Stack:** Java 8 / Spring Boot、Vue 3 / TypeScript、Three.js、现有评测与温室仿真服务。

**Spec:** `docs/superpowers/specs/2026-09-29-horti-m3-platform-adaptation-design.md`

## Global Constraints

- 默认场景 ID 为 `HORTI_M3_TOMATO`，年份 2025、处理 CK、品种广辉201、模拟窗口 56 天。
- Horti-M3 环境采样间隔为 30 分钟；在线 24 小时运行每步 30 分钟，共 48 步。
- 全棚占地 1600 m²；试验种植面积 252 m²；实验植株总数 840 株。
- 模拟输出标为 `SIMULATED`，参数未校准；不得将页面/报告表述成 M3 实测回放。
- lux 与 PPFD 不互换；未有数据/转换依据前，PPFD 只作为模型内部代理量。
- 论文未给出的跨数、基质根区体积、气候控制设备容量和品种系数继续明确标为假设。
- 保留工作区已有修改；不运行或新增测试。

---

### Task 1: 定义统一 Horti-M3 场景资料

**Files:**
- Create: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/profile/HortiM3Profile.java`
- Create: `YOLO_AI_CropDisease_Detection_Vue/src/views/digitalTwin/hortiM3Profile.ts`

**Interfaces:**
- Java 公共常量：场景 ID、默认年/日期/天数/品种/处理、30 分钟步长、全棚/试验区几何、试验小区/株数、六穗整枝元数据、模拟/校准标签。
- TypeScript 导出同名的 `HORTI_M3_PROFILE` 只读对象，供 3D 与页面标签使用。

- [x] 增加 Java 配置类，计算全棚面积 `40×40`、试验面积 `14×18`、试验株数 `14×60`，保存 2025-04-19 默认开始日与 56 天窗口。
- [x] 增加 TypeScript 配置对象，保存相同的可视化几何/处理/品种/日期元数据，并将未知跨数写为 `schematic`。
- [x] 在 `.planning/horti-m3-simulation-reference/findings.md` 写明配置出处与“假设/已知”字段边界。

### Task 2: 后端日级推演与资源/面积适配

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/eval/PerformanceEvaluationService.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/eval/EvaluationController.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/engine/TomatoSimulationEngine.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/eco/SoilParameters.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/eval/ResourceRates.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/eco/ManagementEconomicsModel.java`

**Interfaces:**
- `PerformanceEvaluationService.DEFAULT_DAYS` / `STEP_MINUTES` 引用 `HortiM3Profile`。
- `EvaluationController` 的 `matrix`、`series` 和单批运行元数据包含 `profileId`、`year`、`cultivar`、`treatment`、`sourceStatus`。
- 物理面积与试验种植面积分别从 `HortiM3Profile` 读取；旧未校准能耗、水肥常量继续标为假设。

- [x] 用 M3 日期/季长/半小时步长替换评测默认 120 天/15 分钟；初值保留为明确的模拟假设常量，不宣称来自数据集。
- [x] 用 1600 m² 全棚面积更新微气候体积标尺，用 252 m² 试验区面积更新作物/根区面积折算；根区有效深度继续命名并标注为假设。
- [x] 将场地纬度从旧项目城市改为论文所在地哈尔滨的近似纬度，并将其注释为城市级近似、非论文精确坐标。
- [x] 更新能耗和施肥/滴灌面积换算注释，保留其未校准状态。
- [x] 评测结果接口携带 M3 场景元数据与 `SIMULATED_UNCALIBRATED` 来源状态。

### Task 3: 后端在线运行对齐 M3 采样

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/service/AgentRunService.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/model/AgentDeviceCodes.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/engine/TomatoDecisionPolicy.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/engine/TomatoSimulationEngine.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/eval/ResourceRates.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/tool/GreenhouseStateTool.java`

**Interfaces:**
- 新建默认 AgentRun 不把旧 greenhouse 记录冒充成 Horti-M3 初值；初始帧来源为模拟 M3 场景。
- `DEFAULT_TICK_MINUTES=30`、`TOTAL_STEPS=48`；旧运行读取每个运行保存的步长和模型版本。
- 新设备代码 `HEATING` 对应模型中明确假设的加热动作与资源消耗；设备存在性有论文依据，控制容量与响应量为假设。

- [x] 将新 AgentRun 模型版本与默认时间改为 Horti-M3 场景版本，默认初始化不读取旧温室历史。
- [x] 对没有显式选择旧温室来源的新建运行使用 profile 初始状态，并把首帧写成 `SIMULATED`，附 `profileId` 与未校准标签。
- [x] 增加加热代码、标签、规则阈值、逐步温升代理量与电耗；所有加热出力数值写明是示意假设。
- [x] 保留排风/补光/CO₂/湿帘等旧模拟代码，但在 M3 页面/输出中注明论文没有提供其对应控制时序或设备容量。
- [x] 让智能体状态摘要报告 30 分钟步长、M3 场景 ID 与模拟来源。

### Task 4: 同步推演入口、农事规划与报告默认值

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/config/AgriPlanProperties.java`
- 默认天数在 `AgriPlanProperties` 中引用 profile，保留现有外部配置覆盖。
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/controller/AgentReportController.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/tool/ProductionReportTool.java`
- Modify: `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/agent/report/ProductionReportService.java`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/agentSimulation/index.vue`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/api/eval/index.ts`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/digitalTwin/index.vue`

**Interfaces:**
- 缺省规划、报告、评测与前端演示天数都为 56；已有明确传入 `days` 的请求继续按请求运行。
- 前端离线序列默认批次 `HORTI-M3-2025-CK-GUANGHUI201`，并标记示例来源。

- [x] 将 Java 报告工具/控制器/规划属性缺省值同步到 profile 默认 56 天，去除仍面向用户的 120 天文案。
- [x] 将前端评测默认批次、日期与天数同步到 2025 M3 场景；离线示例使用 252 m² 试验面积与 840 株并保留 demo 标识。
- [x] 在农事规划结果头部加入 M3 场景、模拟标签与参数未校准说明。
- [x] 在数字孪生顶栏展示 profile 元数据、模拟步长与试验小区/试验株数。
- [x] 将土壤描述改成基质/根区模型量，区分论文可测字段与模型派生/假设字段。

### Task 5: 重建数字孪生试验场布局

**Files:**
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/digitalTwin/greenhouseLayout.ts`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/digitalTwin/scene.ts`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/digitalTwin/equipment.ts`
- Modify: `YOLO_AI_CropDisease_Detection_Vue/src/views/digitalTwin/siteDetails.ts`
- 六穗展示上限在 `scene.ts` 的植株实例参数中设置；现有植物生成器复用。

**Interfaces:**
- 3D 场景尺寸由 `HORTI_M3_PROFILE` 产生；小区位置为示意网格，不表示论文没有披露的实测地块坐标。
- 每小区以两行各 30 株表示 60 株，行距 0.6 m、株距 0.4 m；14 小区合计 840 株几何实例，若影响性能则通过同一配置稀疏化视觉株数并显示代表株数/总株数。
- 六穗整枝展示保持每株最多六穗。

- [x] 将壳体改为 40×40 m、檐高 4.5 m、脊高 6 m；多跨只作为独立可视化示意。
- [x] 在 40 m 外壳内布置 14 个 18 m² 试验小区；使用两条行线/区展示两行栽培，每区 60 株。
- [x] 同步苗床、滴灌主管、吊蔓线、土壤湿斑和传感器挂点，使几何不再依赖旧 26×13 m 棚体。
- [x] 用情景注释说明未公开跨数、传感器空间布点与热源/风机容量为示意；M3 数据光照 lux 与作物模型 PPFD 不合并冒充同一单位。

### Task 6: 更新项目说明与当前计划状态

**Files:**
- Modify: `docs/tomato-greenhouse-agent.md`
- Modify: `.planning/horti-m3-simulation-reference/findings.md`
- Modify: `.planning/horti-m3-simulation-reference/progress.md`
- Modify: `.planning/horti-m3-simulation-reference/task_plan.md`

- [x] 将原 26×13 m/四床/104 株描述改成 M3 默认场景与数据边界，保留旧运行版本说明。
- [x] 记下 M3 已公开事实、当前未校准量、原始资料缺失与后续数据导入需求。
- [x] 更新阶段到已实施/静态核对完成；不声称测试通过或数据已校准。

## 最终结果

全部场景适配步骤已实施。额外对齐处方工具的运行步长与水量单位，刷新派生参数登记，并恢复前端服务。前后端构建成功，未运行测试。原始数据导入、参数校准和精度验证仍待后续工作，当前日级视图的离线示例已明确标注。
