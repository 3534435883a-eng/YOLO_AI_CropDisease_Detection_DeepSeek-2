# 接口审计与最小设计

审计对象：GitHub `3534435883a-eng/YOLO_AI_CropDisease_Detection_DeepSeek-2`，基线 `57b6d21`，2026-10-14。

## 复用核对

所有源码位置以仓库中的 `YOLO_AI_CropDisease_Detection_SpringBoot/src/main/java/com/example/Ece/` 为根。

| 接口/控制器 | 真实请求与输出 | 数据、权限和超时 |
| --- | --- | --- |
| `agent/controller/AgriPlanController`，POST `/ai/agri/plan/situation/parse` | JSON `{text}`；`Result {code,msg,data}`；data含fields、input、missing、conflicts、notes | `AgriSituationNlParser`调用DeepSeek，逐字段登记/范围校验，返回待确认草稿。AI异常也可能code=0、fields=[]，适配层映射422。原文非空检查未验证substring，本层补检查。非SSE，DeepSeek connect10s/read60s；parse本身没有独立总预算。 |
| `agent/eval/EvaluationController`，POST `/eval/runs` | JSON `{batchId,seed,days}`；默认seed20250419，days56。同步完整batch | `PerformanceEvaluationService`现有五模型耦合、30分钟步长，7档策略；seed决定合成天气相位，无LLM。不存在用户农情、面积等输入参数。同步运行无显式超时与days上限。 |
| 同上，GET `/eval/{batchId}/matrix` | `batchId/profile/seed/days/elapsedMillis/outcomes`；20种原始指标，本层选10项 | 内存约5批，非严格LRU；失效HTTP200但code=`EVAL_BATCH_NOT_FOUND`。适配层映射410，成功后私有保存原响应与精简矩阵；报告不依赖上游缓存。 |
| `agent/m3/M3LiveController`，POST `/m3/live/start` | `{year:2025}`，支持已导入2023/2024/2025；返回runId与模型快照 | `M3LiveService`读`M3ObservationService`标准化观测，验证清单SHA256，读跨年校准参数，创建独立模型及日志。并非实时棚内遥测。可能错误HTTP200业务code。 |
| 同上，GET `/m3/live/{id}` | query `compact`和`afterCursor`，返回已消费观测及快照 | 服务读取、回放与恢复模型日志；本次最小外部层不暴露M3运行ID和日志目录。 |
| 同上，POST `/m3/live/{id}/scenario/{operation}` | configure `{autoEvents,autoActuation,taskId?}`；trigger `{type}`；endWeather `{}`；repair `{device}`；decide `{question,apply,taskId?}` | `ScenarioSession`只作用于模型；部分命令会触发`ScenarioAiService`异步推理或应用仿真目标。非decide命令也可能自动触发AI。故最小层不透传此动态入口；不开放任意operation。 |
| `agent/controller/AgentChatController`，POST `/ai/agent/chat` | `AgentChatRequest`，SSE step/final/error；默认`allowSimulationActions=true` | `AgentOrchestrator`现有RAG/工具/模型编排总预算90s，emitter95s；断开时取消。工具范围宽于本适配层，不能直接当外部通用chat。未复刻它，报告用实际工具结果模板。 |
| `agent/controller/KnowledgeController`，GET `/ai/knowledge/status` | 块/向量/来源数量、索引快照、embedding探针年龄及登记来源 | 使用现有知识库，探针TTL30s、embedding默认3s。未加载过索引时可能只读装载。状态不证明已RAG检索。禁止reload/reingest。 |
| `agent/controller/AgriEnvironmentController`，GET `/ai/agri/environment/current` | 环境字段、observedAt、source及说明 | `AgriEnvironmentService`外部天气取数默认3s，未配置或失败退回`SIMULATED_LOCATION`；`OBSERVED`是外部天气服务返回，并非实棚传感器。必须显示原source，不将其覆写进用户草稿。 |

上述四个控制器未声明认证要求，pom未引入Spring Security，源码未发现全局HTTP鉴权过滤器/拦截器。原接口不能直接对公网暴露。既有部署的外围网关权限仍可能不同；适配层可使用独立的上游Bearer环境变量。

## 边界与方案选择

1. **独立HTTP服务（采用）**：不改变后端生命周期和依赖；复用真实接口；需要单独运行小进程。
2. **Spring Boot内增加模块**：少一个进程，但需接触原构建、路由及安全边界，不适合本轮保留原系统要求。
3. **MCP桥**：仅在账号确认缺少API节点且支持MCP后考虑；不能凭空编造平台安装格式。

API节点/工具 → 本层Bearer与校验 → 原农情解析/环境/知识状态 → 保存分析ID；分析ID → 原eval runs/matrix → 保存评测ID；两个关联ID → 指标比较与人工复核报告。

`agri.evaluate`必须显式发送`acknowledgeReferenceScenario=true`。它确认调用方理解固定场景边界，不声称是用户批准生产操作。农情不会注入不支持该参数的接口。只可研究番茄参考场景；其他作物描述可以作为草稿分析，但不能声称已仿真该作物。

## 错误及可信性

- 原接口HTTP与Result业务码双层判断；不泄露原错误正文、堆栈或本机路径。
- 上游POST不自动重试。调用键保存成功/失败；PENDING在重启后仍拒绝重放，管理员先核对后归档。
- 本地SHA256用于结果变更核对与原响应溯源，不是签名、可信时间戳或第三方认证。原始证据只允许本机管理员读取。
- 单进程、单研究团队令牌；不宣称多租户、分布式锁或无限证据保留。外部只开放三个精确POST路径，重定向被禁止。
- 适配层断开HTTP不会取消原同步仿真；超时表示结果不确定。原Spring接口被保留，不替它重构取消机制。

## 已核对的平台及赛事来源

- [用户提供的比赛介绍](https://www.saihuan.net/article/keji/299.html)：作品简介200字内；应用方案PDF20页/50MB内；DuMate分享作品链接；平台执行全程录屏或工作流截图。页面标注截止2026-10-15，报名最终信息以官方为准。
- [DuMate技能管理官方文档](https://cloud.baidu.com/doc/Dumate/s/5mmydgic3)：支持URL、zip/md安装技能。本轮交付md技能，未执行账号安装。
- [DuMate OpenAPI官方文档](https://cloud.baidu.com/doc/Dumate/s/ymu8cfo0x)：它是外部调用DuMate会话的接口，不能据此证明某账号存在第三方HTTP Workflow节点。
- 技能开发指南页面多次获取超时，未以猜测补全其详情。账号节点能力用户回复“尚未确认”；所有Workflow配置明确为条件性手动指南，不假称可直接导入的官方DSL。

## 2026-10-15 情境驱动审计

- 用户明确：大棚模型用本项目现有模型；重点是图像、本地知识、规划和按对话人实际情况追问，不要固定全流程。
- Flask有predictImg与9份既有权重。原Spring /flask/predict传URL/权重并写识别记录，Flask输出图还调用/files/upload；不能原样公开这条任意URL/权重接口。5000当前未监听，不能把图像新Skill记作端到端成功。Python312具备torch/ultralytics/flask/cv2，但尚未运行本轮识别。
- KnowledgeSearchTool复用KnowledgeRetriever及CitationFormatter，支持query/crop/topN、引文、低分和降级标记；没有独立只读HTTP检索入口。status不是检索。VisionExplainTool复用已核验类别映射；不匹配/无映射会返回明确终态，不可猜病名。
- 农业规划POST /ai/agri/plan/deduce接收situation/question/seed/days/taskId/simulationRunId/requestId并返回SSE，默认预算240秒，有界线程池2+队列8。未关联任务时会计算参考基线；关联FarmTask上下文时明确不计算固定旧场景评测。需要流式转译/异步任务与独立超时审计。
- FarmTaskController /agent/tasks已提供创建、读取、补充事实、证据、对话、观测、动作及报告；应只复用所需资料和证据能力，不外放动作状态/设备控制。FarmTaskService可在同一任务关联M3当前状态和来源边界。
- AgentChat会通过模型自主选工具，allowSimulationActions=false不等于强制只调用knowledge.search。不能把它作为安全的任意工具代理；优先增加窄只读桥直连知识Tool。
- 现适配层仍只开放analyze/evaluate/report，decide是本机选择；旧40项测试不覆盖拟新增视觉、检索、规划与动态会话。
