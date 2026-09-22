# 农业专属智能体（RAG + 知识图谱 + LLM 编排）设计

- 日期：2026-09-22
- 赛题：第八届全球校园人工智能算法精英大赛·算法主题赛「AI+农业」**赛题五 农业智能体与大模型决策服务赛**
- 前置依赖：`2026-09-20-deepseek-backend-proxy-design.md`（AI 代理）、`2026-09-21-tomato-greenhouse-agent-design.md`（温室智能体仿真）
- 定位：**甲+ 方案（L2 专属智能体）**。采集与控制为模拟层，AI 分析（检索、编排、决策、可解释）为真实层。

## 1. 目标

把现有"单轮 LLM 问答"升级为**面向农业场景的专属智能体**：能够理解用户意图、自主选择并调用专属工具（知识检索、视觉识别、温室状态、沙盘推演、处方草拟、报告导出），多步执行后输出**有出处、可执行、可审计**的农事管理方案、病虫害防治建议与水肥调控处方。

成功标准：

1. 回答带可定位引用（`[n]` → 病名/字段/片段号），无依据时拒答。
2. 检索有可复现指标（Top-3 命中率、引用正确率），并有 A/B 对照数据。
3. 智能体每一步可观测（工具、入参摘要、耗时、结果摘要、降级标记）。
4. 不自动施药、不自动改变设备状态；写操作需人工确认。
5. 遗留接口与既有 18 项后端测试不被破坏。

## 2. 边界（明确不做）

向量数据库、图数据库、多智能体协作、模型微调、实时传感器接入、真实执行器控制、自动用药、通用领域问答、用户级配额与计费、**3D 场景与生长动画渲染**（改为 2D 生长曲线 + 多目标雷达图）、**重排模型 bge-reranker**（显存/收益不划算）、**知识图谱三维可视化**（降级为数据表 + 2D 关系图）。

## 3. 数据来源标记（沿用既有体系，不得混用）

| 标记 | 含义 |
|---|---|
| `REAL` | 真实运行的分析产物（检索、识别、LLM 生成） |
| `LEGACY_HISTORY` | 旧库历史记录 |
| `SIMULATED` | 仿真推演（环境数据、设备动作、资源消耗） |
| `VISION_SIGNAL` | 视觉识别结果，状态固定 `PENDING_REVIEW` |
| `MANUAL` | 人工录入或人工确认 |

任何面向用户的输出必须携带来源标记；`SIMULATED` 与 `VISION_SIGNAL` 不得表述为实测结论。

## 4. 架构

```
Vue 前端
  │  ① POST /api/ai/agent/chat      (SSE: step/citation/delta/final/error)
  ▼
Spring Boot
  ├── LLM 编排层  AgentOrchestrator
  │     意图理解 → 工具选择 → 执行 → 观察 → 再决策（≤6 步）
  ├── 工具层      AgentToolRegistry / AgentTool
  │     T1 knowledge.search   T2 vision.detect   T3 greenhouse.state
  │     T4 sim.simulate       T5 prescription.draft   T6 report.export
  ├── 守门层      GuardrailService（安全约束、越权拦截、拒答）
  ├── 决策层      TomatoDecisionPolicy（既有规则引擎，确定性）
  ├── 推演层      TomatoSimulationEngine（既有，纯函数、可复现）
  └── 检索层      KnowledgeRetriever ──HTTP──► Flask /embed (bge-small-zh-v1.5)
  │
  ▼
MySQL（create-only 迁移 V20260922_01）
  agent_knowledge_chunk / agent_knowledge_node / agent_knowledge_edge / agent_step_trace
```

**双脑分工（核心叙事）**：LLM 负责理解与表达，规则引擎负责安全与确定性，沙盘负责后果验证；三者职责分离，可解释、可审计。

## 5. 组件与职责

| 组件 | 包/位置 | 职责 | 依赖 |
|---|---|---|---|
| `AgentOrchestrator` | `agent/orchestrator` | 循环控制、prompt 组装、终止条件、SSE 事件发射 | LLM 客户端、工具注册表、守门层 |
| `AgentTool` | `agent/tool` | 工具契约接口 | — |
| `AgentToolRegistry` | `agent/tool` | 工具注册、schema 校验、权限与超时元数据 | 各工具实现 |
| `KnowledgeChunker` | `agent/rag` | 离线切块（500 字 / overlap 80）+ 元数据 | `disease` 表、KG 文本 |
| `KnowledgeRetriever` | `agent/rag` | BM25 + 向量 + RRF 融合 → top-k | chunk 存储、`EmbeddingClient` |
| `EmbeddingClient` | `agent/rag` | 调 Flask `/embed`，超时/失败降级 | Flask |
| `KnowledgeGraphService` | `agent/kg` | 三元组 1–2 跳查询与子图导出 | node/edge 表 |
| `CitationFormatter` | `agent/rag` | 命中块 → `[n]` 与定位信息 | 检索结果 |
| `PrescriptionService` | `agent/service` | 规则结果 → 结构化处方（做什么/何时/用量/注意/风险） | `TomatoDecisionPolicy` |
| `GuardrailService` | `agent/guard` | 安全与合规校验（见 §9） | — |
| `AgentStepTraceRepository` | `agent/repository` | 每步落库（审计与可观测） | MySQL |
| `ReportExporter` | `agent/service` | 运行摘要+决策+处方+引用 → **自包含 HTML**（浏览器打印为 PDF） | 无新依赖；中文零字体配置风险 |

前端新增：`src/views/agentCenter` 内的对话面板（SSE 消费 + `[n]` 引用点击定位 + 步骤时间线）；`src/api/agent/chat.ts`。

## 6. 存储 schema（create-only 迁移 `V20260922_01__agent_knowledge_rag.sql`）

不改动 `disease`、`greenhouse` 等遗留表；所有新表以 `agent_` 前缀、InnoDB、utf8mb4。

| 表 | 关键列 | 说明 |
|---|---|---|
| `agent_knowledge_chunk` | `id`, `source_table`, `source_id`, `crop_type`, `disease_name`, `field_type`(SYMPTOM/CAUSE/CONTROL/OTHER), `chunk_no`, `content`, `embedding` VARBINARY(4096)（512 维 float32）, `embedding_model`, `content_hash`, `created_at` | 唯一键 `(source_table, source_id, field_type, chunk_no)`；`content_hash` 用于幂等重建 |
| `agent_knowledge_node` | `id`, `node_type`(CROP/DISEASE/SYMPTOM/PATHOGEN/PESTICIDE/ENV_FACTOR), `name`, `alias`, `source_name`, `source_url`, `version` | 唯一键 `(node_type, name, version)` |
| `agent_knowledge_edge` | `id`, `head_id`, `relation`(HAS_SYMPTOM/CAUSED_BY/TREATED_BY/FAVORS/AFFECTS/ALTERNATIVE), `tail_id`, `weight`, `source_name`, `version` | 唯一键 `(head_id, relation, tail_id, version)` |
| `agent_step_trace` | `id`, `session_id`, `run_id`, `step_no`, `tool_name`, `input_digest`, `output_digest`, `duration_ms`, `degraded`, `status`, `created_at` | 审计与可观测，含降级标记 |

## 7. 检索规格（锁定参数，可复现）

| 项 | 规格 |
|---|---|
| 切块单位 | `disease` 表每条记录的 `症状`/`诱因`/`防治` 各成一块；超长按 500 字切分，overlap 80 |
| 元数据 | 作物、病名、字段类型、条目 ID、来源（`LEGACY_HISTORY`） |
| 关键词检索 | 中文 **bigram** 倒排，内存索引；BM25 `k1=1.2`、`b=0.75`；`topK=20` |
| 向量检索 | `bge-small-zh-v1.5`，向量 L2 归一化，余弦相似度；`topK=20` |
| 融合 | **RRF**，`k=60`，两路等权 → 取 `topN=5` |
| 重排 | `bge-reranker-base`（约 1.1GB）**默认关闭**，时间允许时作为增益项并留评测对比 |
| 低分处理 | 融合分数低于阈值 τ（初值 `0.016`，两路均命中 top-1 时约 0.033）→ 查询改写一次（同义病害名/症状词扩展）；仍低 → **拒答**。τ 由评测集标定后写入配置 |
| 降级 | `/embed` 不可用或超时（默认 3s）→ BM25-only，回答中标注"降级：仅关键词检索"，并在 `agent_step_trace.degraded` 记录 |
| 幂等重建 | `POST /api/knowledge/reindex` 按 `content_hash` 增量更新，不重复嵌入 |

## 8. 知识图谱规格

- **节点类型**：`CROP`、`DISEASE`、`SYMPTOM`、`PATHOGEN`、`PESTICIDE`、`ENV_FACTOR`。
- **关系类型**：`HAS_SYMPTOM`、`CAUSED_BY`、`TREATED_BY`、`FAVORS`、`AFFECTS`、`ALTERNATIVE`。
- **来源**：① 本项目 `disease` 表半自动抽取；② 权威公开数据源补全——
  - Nature《Scientific Data》2025《A knowledge graph for crop diseases and pests in China》
  - 《设施番茄、黄瓜的病虫害知识图谱构建数据集》
  - 《棉花病虫害知识图谱构建数据集》
  每条节点/边必须记录 `source_name` 与 `version`，**不得无出处入库**。
- **用途**：① 作为检索扩展（症状词 → 病名 → 药剂）；② 作为独立工具供编排层调用；③ 前端子图可视化（ECharts graph）。

## 9. 编排循环协议

| 项 | 规格 |
|---|---|
| 状态机 | `INTENT → PLAN → ACT → OBSERVE → (回 PLAN 或 FINALIZE) → DONE/REFUSED/ERROR` |
| 步数上限 | 6 步；同一工具最多 2 次；**禁止完全相同的入参重复调用** |
| 超时 | 单步 20s，整体 90s；超限→强制汇总已有证据输出 |
| LLM 角色 | 只做意图理解、工具选择、结果组织与表达；**不直接产生设备指令** |
| 事件流（SSE） | `step`（工具/入参摘要/状态）、`citation`（引用条目）、`delta`（正文增量）、`final`（结论+审计 ID+来源标记）、`error` |
| 终止条件 | 输出终稿 / 触发拒答 / 步数或超时超限 / 守门拦截 |

### 工具契约

| 工具 | 入参 | 出参 | 权限 | 降级 |
|---|---|---|---|---|
| `knowledge.search` | `query`, `crop?`, `topN=5` | 命中块列表 + 分数 + 定位信息 | 只读 | 向量不可用→BM25 |
| `vision.detect` | `imageRef`, `crop` | 标签 + 置信度 + 证据链接（`PENDING_REVIEW`） | 只读 | Flask 不可用→跳过并说明 |
| `greenhouse.state` | `runId` | 环境快照 + 风险等级（`SIMULATED`） | 只读 | 无运行→返回空并说明 |
| `sim.simulate` | `runId`, `devicePlan` | 推演对比结果（风险/资源） | 只读（纯计算） | 无 |
| `prescription.draft` | `disease?`, `state?`, `question` | 结构化处方草稿 + 风险提示 | 草稿 | 无 |
| `report.export` | `runId`, `sessionId` | 自包含 HTML 报告链接 | **写（需人工确认）** | 无 |

## 10. 引用与答案格式

- 正文引用：`[1]`、`[2]`，点击定位到"病名 / 字段类型 / 片段号"。
- 每个引用条目附检索分数与来源标记。
- 回答末尾固定三段：**结论**（做什么）、**依据**（`[n]` 列表）、**风险与注意事项**。
- 涉及药剂：必须给出"需人工确认/遵循当地用药规范"提示，且**不提供自动执行入口**。
- 信息不足：明确拒答并给出人工咨询建议，不臆造。

## 11. 守门规则（GuardrailService）

1. 涉及农药/化肥具体用量 → 必须带引用且标注"需人工确认"。
2. 任何设备动作只能生成**处方草稿**，不得直接调用 `/agent/runs/{id}/devices/{code}/manual`。
3. 未取得引用支撑的专业结论 → 拦截并改为拒答。
4. 降级状态必须显式披露。
5. `SIMULATED`/`VISION_SIGNAL` 内容不得表述为实测。
6. 单次会话输出长度上限与工具调用上限（防失控与成本失控）。

## 12. 错误处理与降级矩阵

| 故障 | 行为 |
|---|---|
| `/embed` 超时/失败 | BM25-only + 降级标注 + trace 记录 |
| 检索低分/无命中 | 改写一次 → 仍低分则拒答 |
| LLM 超时/限流/认证失败 | 复用 `DeepSeekService` 错误码（`AI_RATE_LIMIT`、`AI_UPSTREAM_ERROR` 等），**返回已获证据**并说明 |
| 单工具异常 | 该步标记失败，编排层决定继续或终止 |
| 数据库不可用 | 返回 `AGENT_STORAGE_ERROR`，提示先执行迁移（沿用既有映射） |
| 越权写操作 | 直接拦截并记录审计 |

## 13. 测试与评测

| 类型 | 内容 |
|---|---|
| 单元测试 | 切块边界与 overlap、内容哈希幂等、BM25 打分、RRF 融合、引用格式化、守门 6 条规则、工具 schema 校验 |
| 编排测试 | mock LLM 固定工具序列，断言状态机迁移、步数上限、重复调用拦截、终止与降级分支 |
| 检索评测（**E-A**） | 建 40 问评测集（`docs/eval/retrieval-questions.md` + 期望出处），报 **Top-3 命中率**与**引用正确率** |
| A/B 对照（**E-B**） | 同题同输入四档：裸 LLM / +RAG / +RAG+KG / +agent，比较术语命中率、引用正确率、可执行项数、评审分 |
| 外部对照（口径） | 从 **AgriEval** 中文农业基准抽样，作为回答专业性的外部可比口径 |
| 视觉指标（**E-C**，对应 T6） | 用公开作物病害数据集跑验证集，报真实指标（不伪造） |
| 真实调研（**E-D**，材料侧） | 5–10 份农户/合作社/农技员问卷或访谈；**不由代码产出**，用于技术方案第二章 |

## 14. HTTP 接口契约（新增）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/agent/chat` | SSE 流式智能体会话（事件见 §9） |
| GET | `/api/knowledge/search?q=&crop=&topN=` | 检索调试与评测（返回分数与定位） |
| POST | `/api/knowledge/reindex` | 幂等重建索引（离线/维护用） |
| GET | `/api/agent/runs/{runId}/report.html` | 报告导出（自包含 HTML + 打印样式，前端提供"打印/另存为 PDF"） |
| GET | `/api/knowledge/graph?name=&depth=2` | 子图查询（可视化用） |

遗留接口 `/api/ai/chat`、`/agent/**` **保持不变**。

## 15. Flask 侧改动

- 新增 `POST /embed`：入参 `{texts: [...]}`，出参 `{vectors: [[...]], model: "bge-small-zh-v1.5", dim: 512}`。
- 模型**懒加载**（首次请求时加载并缓存），失败时返回结构化错误，不影响既有 `/predictImg` 等接口。
- 依赖新增：`sentence-transformers`（模型来源 ModelScope/HF 均可，已验证网络可达）。
- **顺带修复**：`predict/predictImg.py` 的置信度放大（映射到 90%–99.99%）与 `conf` 入参被 `self.conf = 0.1` 覆盖两处缺陷，改为可配置阈值 + 原始置信度，并补单元测试。

## 16. 部署与迁移顺序

1. 备份 `cropdisease` 数据库。
2. 执行 `database/migrations/V20260922_01__agent_knowledge_rag.sql`（create-only，**不得重新导入 `cropdisease.sql`**）。
3. 启动 Flask（5000）→ 后端（9999）→ 前端（8100）；首次运行调用 `POST /api/knowledge/reindex` 建索引。
4. 配置环境变量：`DEEPSEEK_API_KEY`、`EMBEDDING_BASE_URL`（默认 `http://127.0.0.1:5000`）、`RAG_TOP_K`（默认 20，每路召回）、`RAG_TOP_N`（默认 5，融合后）、`RAG_RRF_K`（默认 60）、`RAG_MIN_SCORE`（默认 0.016）、`AGENT_MAX_STEPS`（默认 6）、`EMBEDDING_TIMEOUT_MS`（默认 3000）。

## 17. 里程碑与工作量

| 阶段 | 内容 | 工作量 |
|---|---|---|
| W1-D1 | 切块 + 知识表 + 迁移 + BM25 检索 | 1 天 |
| W1-D2 | Flask `/embed` + 向量检索 + RRF + 降级 | 1 天 |
| W1-D3 | 编排层 + 工具注册表 + SSE | 1.5 天 |
| W1-D4 | 引用格式 + 守门 + 审计落库 | 1 天 |
| W2-D1 | 评测集 E-A + A/B 脚本（E-B） | 1 天 |
| W2-D2 | 处方与报告导出 | 1 天 |
| W2-D3 | KG 抽取与补全 + 子图接口 | 1.5 天 |
| W2-D4 | 前端对话面板与步骤时间线 | 1 天 |
| W2-D5 | 作物生长模型（§20） | 2 人日 |
| W3-D1 | 性能评测平台与四档对照（§21） | 1.5 人日 |
| W3-D2 | 前端对比展示（矩阵表/雷达/曲线/堆叠图） | 1 人日 |
| W3 | 视觉指标取证、公开集验证、材料配套 | 与材料生产并行 |

合计约 **9 人日**（45h/周配置下 W1–W2 完成主体）。

## 18. 风险与对策

| 风险 | 对策 |
|---|---|
| 语料仅 100 条，检索上限低 | 用权威 KG 数据集补全至数百条；评测如实报告样本量 |
| 向量模型下载失败 | 已验证 ModelScope/HF 可达；仍失败则 BM25-only + 如实标注降级 |
| LLM 幻觉 | 强制引用、无依据拒答、守门拦截 |
| 智能体失控/成本 | 步数/超时/工具次数三重上限 |
| 仿真被误读为实测 | 全程来源标记 + 材料统一口径 |
| 赛程时间 | 每阶段结束均为可交付状态；材料（技术方案/视频/PPT）与开发并行 |

## 19. 参考与出处（材料附录用）

- `SCAI-BIO/SEEDS`：农业 RAG + 领域知识图谱问答（三元组 KG、相似度展示、可解释可视化）
- `ningkaikok/novel-rag`：中文 RAG 四层检索（向量 + BM25 + RRF + 重排）、引用定位、检索评测方法论
- `Asuna001/KG-Crop`：中文作物 KG 问答（意图分类 + NER + 模板，仅作 baseline 对照）
- Nature《Scientific Data》2025：中国作物病虫害知识图谱
- 《设施番茄、黄瓜的病虫害知识图谱构建数据集》《棉花病虫害知识图谱构建数据集》
- `AgriEval`：中文农业大模型基准
- Ultralytics YOLO11（**AGPL-3.0**）、`ffmpeg`（GPL-3.0）、Vue 前端模板（MIT，Copyright (c) 2021 lyt-Top）

## 20. 作物生长模型（2026-09-22 新增立项）

**目标**：为智能体决策提供"偏现实"的后果计算，使模型成为 **AI 性能的验证平台**（而非动画）。

**组件**：`TomatoCropGrowthModel`（`com.example.Ece.agent.crop`，Java 纯函数、确定性、无随机、15 分钟步长），与 `TomatoSimulationEngine` 同频推进。

**状态量**：

| 类别 | 状态量 |
|---|---|
| 发育 | `gdd`（积温，℃·d）、`stage`（SEEDLING/FLOWERING/FRUIT_SET/FRUIT_GROWTH/MATURITY） |
| 形态 | `lai`（叶面积指数）、`plantHeight` |
| 干物质 | `wLeaf`、`wStem`、`wRoot`、`wFruit`（g·m⁻²）、`wTotal` |
| 产量 | `fruitSetRate`、`fruitCount`、`singleFruitWeight`、`harvestableDryWeight` |
| 胁迫 | `temperatureFactor`、`co2Factor`、`waterFactor`、`vpdFruitSetFactor` |

**核心公式**（半机理；参数集中在 `TomatoGrowthParameters`，逐条带 `sourceNote`）：

```
gdd        += max(0, T_avg - T_base)                 T_base = 10℃（按日聚合）
par         = 0.5 × solarRadiation
iAbsorbed   = par × (1 - exp(-k × lai))              k ≈ 0.65
dW          = rue × iAbsorbed × f_temp × f_co2 × f_water   rue ≈ 2.5–3.5 g DM·MJ⁻¹
allocation  = 按 stage 查表（营养期偏叶/茎/根；坐果后果实分配比升至 0.5–0.6）
dLai        = sla × dW_leaf - senescenceRate          sla ≈ 0.02 m²·g⁻¹
fruitSetRate= f_temp(15–30℃ 最优，>32℃ 显著下降) × f_vpd(VPD>2.0 kPa 下降) × f_assimilate
singleFruitWeight += 按坐果后积温增长；MATURITY 阶段触发转色标志
```

**参数出处纪律**：数值取番茄生长模型（TOMGROM / TOMSIM / 国内番茄栽培文献）的**典型区间**，实施时逐条核对并在 `sourceNote` 与材料附录中标注；**不做本地标定**（无田间数据），材料中列为限制与后续工作。**禁止编造具体文献页码**。

**耦合**：`SimulationState`（环境） → `TomatoCropGrowthModel.advance(cropState, env, dt)` → 作物状态；决策层"规则引擎守门不变"，新增**作物导向决策评分**：沙盘预演候选方案对未来 N 步 `wFruit`/`fruitSetRate` 的影响，作为方案选择依据。LLM 与作物模型均不得直接操作设备。

**数值约束**：所有干重、LAI 全程钳制非负并设上限；LAI ∈ [0, 6]；`wFruit ≤ wTotal`；跨越阶段时钳制 `stage` 单调不回退。

**测试要求**（`TomatoCropGrowthModelTest`）：同 seed 同输入可复现（两次运行状态完全相等）；`gdd` 随有效温度单调不减；`lai` 先升后稳（不倒挂）；高温（>32℃）下 `fruitSetRate` 低于适宜温度；水分胁迫 `f_water < 1` 时 `dW` 降低；`wFruit ≤ wTotal` 恒成立。

## 21. AI 性能评测平台（2026-09-22 新增立项）

**目标**：用同一初始条件下的多档对照，量化证明"带 AI 的决策优于不带 AI"，直接产出应用成效证据。

**四档对照**（同 seed、同天气相位、同初始状态）：`P0` 无调控（设备全关）／`P1` 人工固定策略（定时通风+灌溉）／`P2` 现有规则引擎自动／`P3` 智能体决策（agent + 沙盘预演）。

**指标矩阵**（四类，同一批运行中聚合）：

| 类别 | 指标 |
|---|---|
| 产量品质 | `wFruit`、`singleFruitWeight`、`fruitSetRate`、成熟达成时间 |
| 资源效率 | 水/电/CO₂ 累计消耗、单位产量水耗与能耗 |
| 风险控制 | 高温(>32℃)时长、高湿(>85%)时长、VPD>2kPa 时长、病害环境压力积分 |
| 决策质量 | 约束违反次数（应为 0）、引用出处覆盖率、平均决策耗时、拒答率 |

**接口**：`POST /api/eval/runs`（按四档批量推演）、`GET /api/eval/{batchId}/matrix`（指标矩阵）、`GET /api/eval/{batchId}/series`（LAI/干重/坐果时间序列）。

**可复现要求**：同一 `batchId` 参数重复执行，矩阵与序列逐字节一致（写成单测断言）。

**推演地平线（2026-09-22 修正）**：番茄发育由积温驱动，`BASE_TEMPERATURE_C = 10` 时 24℃ 下仅累积 **14 GDD/天**，而 `GDD_FLOWERING = 600` 需约 **43 天**、`GDD_MATURITY = 1500` 需约 **107 天**。因此：① 对照批次的默认地平线为 **120 天**（每日聚合一条序列）；② 短地平线（3–5 天）只能比较**风险与资源类**指标，产量类指标此时尚未分化；③ 需要短地平线展示产量差异时，必须从**预置的成株初始状态**（如 30 天龄、`lai ≈ 2.0`）起跑，并在界面标注初始状态来源。**禁止**通过调低 GDD 阈值来"加速"生长以迎合演示。

**展示**（零新依赖，全部 ECharts）：多目标对比矩阵表 → 四类归一化雷达图 → 生长曲线（多档叠加） → 器官分配堆叠图。

**工作量**：生长模型 2 人日 + 评测平台 1.5 人日 + 前端展示 1 人日 = **4.5 人日**。

### 21.1 落地后回填：被实测证伪的三个设计（2026-09-22）

评测平台跑通后，前两版"智能体择优"逻辑都被数字证伪，记录在此以免重犯：

| 版本 | 做法 | 实测结果 | 结论 |
|---|---|---|---|
| v1 | 单目标加权：`生长×40 − 高温×0.3 − 高湿×0.2 − 资源成本` | P3 产量 295.8 vs P2 296.3（**无差异**） | 权重是拍脑袋的，且 8 步（2h）地平线看不到差异 |
| v2 | 两级：以**本轮最优**为风险门槛 | P3 几乎不灌溉，产量塌到 P2 的 **4%** | "接近最优"会形成逐底竞争，最极端候选定义标准 |
| v3 | 两级：**绝对**湿度/严重度阈值 | P3 产量仍只有 P2 的 **41%**（灌溉被整类排除） | 绝对阈值忽略了灌溉必然抬升湿度这一物理事实 |
| **v4（采用）** | 两级：风险门槛**锚定规则基线**（严重度 +0.3 个百分点、高湿 +60 分钟内），再在其中最大化 `生长 − 成本×0.02 − 高温×0.002` | P3 产量 **3,647.6 kg**（P2 的 1.35 倍、P0 的 4.8 倍）、利润 **+3,071 元**（P2 的 2.66 倍） | 结构上保证"不劣于规则档"，同时允许在生长与成本上取胜 |

### 21.2 场景与耦合的必要修正

1. **高温期场景**：原常年基准情景下温室最高约 29℃，低于规则引擎的 29℃ 通风触发线，导致**通风设备全程未被使用**、高温时长在三档间完全相同（都是 21,615 分钟）。现设第 45–55 天与第 85–95 天外界温度上浮 6℃（示例），风险维度才有区分度。
2. **作物蒸腾耦合**：作物蒸腾才是温室夜间高湿的主因（闭棚常达 90% 以上），而原模型室内湿度只能趋近外界（约 84%），三种高湿型病害**永不触发**。引擎现接受"室内湿度源"参数，评测平台按 `min(12%, LAI×4)` 传入——这是"作物 → 微气候"的反向耦合，也是病害子系统的触发前提。
3. **成本计价口径**：成本必须按**本步用量**乘单价；早期误用累计量，导致 120 天后成本虚高到千万元级（二次增长）。

### 21.3 实测指标矩阵（120 天，seed=20260921，示例参数）

| 策略 | 果实干重 g/m² | 产量 kg | 用水 m³ | 电 kWh | 成本 元 | 利润 元 | 高温 min | 高湿 min | 病害压力 | 严重度 % |
|---|---|---|---|---|---|---|---|---|---|---|
| P0 无调控 | 83.13 | 755.8 | 0.00 | 0.00 | 3,840 | −1,466 | 30,495 | 33,435 | 6,494,941 | 76.79 |
| P1 人工固定 | 81.19 | 738.1 | 288.00 | 19.20 | 4,861 | −2,652 | 30,165 | 36,795 | 6,190,811 | 82.67 |
| P2 规则引擎 | 296.94 | 2,699.5 | 612.00 | 1,515.07 | 7,128 | +1,157 | 27,660 | 0 | 5,224,087 | 79.65 |
| **P3 智能体** | **401.24** | **3,647.6** | 580.80 | 3,451.55 | 8,202 | **+3,071** | 29,655 | 15 | **4,982,858** | 78.80 |

**如实呈现的代价**：智能体档电耗更高（约 2.3 倍，主要来自补光）、高温暴露略高于规则档（29,655 vs 27,660 分钟）——这是"用能耗换产量"的真实取舍，材料中必须一并写出，不得只报收益。全部价格为示例参数，正式材料须替换为当地实际价格并标注来源。

## 22. 番茄生态子系统（2026-09-22 新增立项）

**目标**：把"环境 + 作物"扩展为可演示的**番茄生产生态**——五个子系统在**同一 15 分钟步长**上耦合演化，AI 决策同时作用于全部子系统，并由多目标矩阵（§21）衡量其后果。这不是动画，而是**决策的后果计算内核**。

### 22.1 子系统耦合矩阵

| 从 \ 到 | 微气候 | 水肥土壤 | 作物生长 | 病虫害 | 管理经济 |
|---|---|---|---|---|---|
| **微气候** | — | 蒸发/淋洗 | 光合·发育·蒸腾 | **侵染条件** | 通风/补光能耗 |
| **水肥土壤** | 蒸发增湿 | — | 水分·养分胁迫 | 叶面湿润时长 | 水肥投入 |
| **作物生长** | 冠层遮阴·蒸腾 | 吸水吸肥 | — | 寄主易感性 | 产量 |
| **病虫害** | 病斑改变蒸腾 | — | **减产·品质下降** | — | 药剂投入·减产损失 |
| **管理经济** | 设备动作 | 灌溉施肥 | 环境调控 | 植保处方（草稿） | — |

### 22.2 子系统三：水肥土壤 `SoilWaterNutrientModel`（新增）

**状态量**：`soilMoisturePct`、`ecDsPerM`、`soilPh`、`nitrogenKgPerHa`、`phosphorusKgPerHa`、`potassiumKgPerHa`、`leachedNitrogenKgPerHa`、`irrigationMm`、`nutrientFactor`。

**核心公式**（日尺度聚合，参数集中在 `SoilParameters` 并逐条标 `sourceNote`）：

```
ET0      = 简化 Hargreaves（由日均温、温差与日辐射推算）        # 文献典型参数
Kc       = 按 stage 查表（初期 0.6 → 中期 1.15 → 后期 0.8）      # 番茄作物系数
ETc      = Kc × ET0
ΔS       = irrigationMm + 0 − ETc − drainage − evaporation
养分吸收  = uptakeCoefficient × ΔW（每 kg 干物质所需 N/P/K，文献区间）
EC       += 施肥带入盐分 − 淋洗稀释
pH       += f(肥料类型, 灌溉水)（缓冲，限定 [4.0, 8.5]）
```

**输出**：`nutrientFactor ∈ [0.3, 1.0]` 与已有 `waterFactor`，共同约束 `TomatoCropGrowthModel.dW`。

**耦合实现（2026-09-22 落地）**：`TomatoCropGrowthModel.advance(current, env, minutes, externalStressFactor)` 接受外部胁迫因子并乘进 `dW`；生态循环传入 `externalStressFactor = nutrientFactor × diseaseDamageFactor`（三参重载等价于 1.0，保持既有行为）。另：`NUTRIENT_HIGH` 定为 **90 kg/ha**，与 `initial()` 的初始氮量一致，使初始土壤的养分因子恰为 1.0——否则开局即吃 30% 养分惩罚，与"初始养分充足"的设计意图矛盾（已加零步推进一致性单测守住）。

### 22.3 子系统四：病虫害流行 `PestDiseaseEpidemicModel`（新增）

**状态量**（每种病害一套，至少三种 + 一类虫害）：`inoculumLevel`（菌源 0–1）、`latentProgress`、`severityPct`、`infectionEvents`；虫害为 `pestPopulation`（logistic 增长）。

**流行规则**（环境适宜度驱动，区间取文献典型值；**不宣称预测真实疫情**）：

| 对象 | 适宜温度 | 湿度条件 | 备注 |
|---|---|---|---|
| 灰霉病 *Botrytis cinerea* | 15–22 ℃ | RH > 90% 或叶面湿润 > 4 h | 低温高湿型 |
| 晚疫病 *Phytophthora infestans* | 18–22 ℃ | RH > 90%，叶面湿润 ≥ 4–6 h | 毁灭性，需重点演示 |
| 白粉病 *Oidium* | 20–25 ℃ | RH 50–75%（**高湿反而不利**） | 与灰霉反向，用于检验模型分辨力 |
| 叶霉病 *Passalora fulva* | 20–25 ℃ | RH > 85% | 温室高发 |
| 虫害（粉虱/蓟马/蚜虫） | logistic：`r(T)` 在 20–30 ℃ 最大 | 与湿度弱相关 | 简化种群模型 |

```
infectionRate = fTemp(适宜度) × fMoisture(湿度或叶湿时长) × inoculumLevel × hostSusceptibility(stage)
latentProgress += minutes × fMoisture / (latentPeriodMinutes / max(0.3, fTemp))   # 潜育进度必须受湿度门控
若 latentProgress ≥ 1 → infectionEvents++；severityPct += severityGain×(1 − severityPct)
severityPct → diseaseDamageFactor → 降低净光合与果实品质（反馈给 §20 作物模型）
```

**必须标注**：界面与材料统一写"流行病学为**简化模型**，用于决策对比，不构成疫情预报"。

> **规格修正（2026-09-22，实现后回填）**：原文只让温度驱动潜育进度，而 `fMoisture` 仅进入未被消费的 `infectionRate`；照字面实现会让 45% 湿度与 95% 湿度得到**完全相同**的严重度，三条分辨力测试无法通过。已改为湿度门控（干燥时潜育不推进），与"湿度不足则不侵染"的植保常识一致。

### 22.4 子系统五：管理与经济 `ManagementEconomicsModel`（新增）

**状态量**：`waterUsedM3`、`energyKWh`、`co2UsedKg`、`fertilizerUsedKg`、`pesticideUsedKg`、`laborHours`、`yieldKg`、`marketableYieldKg`、`costYuan`、`revenueYuan`、`profitYuan`、`waterPerYield`、`energyPerYield`。

**公式**：`cost = Σ(用量 × 单价)`；`revenue = 一等品产量 × 单价 + 二等品产量 × 折价`；派生 `profit`、`单位产量水耗/能耗`。**单价与折价系数必须可配置，并在材料中标注取值来源与日期**（无来源则标注为"示例参数"）。

### 22.5 AI 决策面扩展

原有 5 类设备动作（灌溉/通风/补光/遮阳/CO₂）之外，新增两类**处方**：
- **施肥处方**（水肥一体，`DRAFT` 权限，可下发）
- **植保处方**（`DRAFT` 权限，**必须人工确认，禁止自动执行**——延续 §11 守门规则）

### 22.6 验收标准

1. 五子系统在同一循环内以 15 分钟步长推进，`SimulationState` 与各子系统状态在同一事务快照中落库。
2. 同 seed + 同参数重复运行，五子系统状态逐值一致（单测断言）。
3. 每个子系统 ≥3 项单测；病虫害模型在**低温高湿**下灰霉/晚疫严重度显著高于干燥条件，而**白粉病相反**（用于证明模型有分辨力而非单一趋势）。
4. 无灌溉时土壤水分单调下降、无施肥时养分单调下降。
5. 产量不得为负，`marketableYieldKg ≤ yieldKg`，`severityPct ∈ [0, 100]`。

## 23. 权威知识库语料 ingest（2026-09-22 新增立项）

**目标**：把 RAG 语料从现有 **100 条**扩展到**千级**，且**每条可溯源**——这是"知识检索的准确性"考察点的物质基础。

### 23.1 语料来源分层

| 层级 | 来源 | 用途 | 入库方式 |
|---|---|---|---|
| **A 权威数据库/标准** | ICAMA 农药登记数据中心；农业行业标准 `NY/T`、地方标准（`dba.sacinfo.org.cn`） | 药剂登记信息、防治技术规范 | 结构化录入**摘要条目** + 出处 URL |
| **B 权威数据集/论文** | 《设施番茄、黄瓜的病虫害知识图谱构建数据集》；Nature《Scientific Data》2025 中国作物病虫害 KG；《棉花病虫害知识图谱构建数据集》 | 病害—症状—防治—环境关系（KG 三元组与知识块） | 下载后转换为三元组 + 知识块 |
| **C 公开识别数据集** | PlantVillage、IP102（虫害） | **仅作视觉模型验证集（E-C）**，不入知识库 | 评测用 |
| **D 评测基准** | AgriEval（中文农业大模型基准） | 回答专业性的外部对照 | 抽样评测 |
| **E 本项目旧库** | 现有 `disease` 表 100 条 | 基线语料 | 复用 §7 切块器 |

### 23.2 存储扩展（迁移 `V20260923_01__agent_knowledge_source.sql`，create-only）

- 新增 `agent_knowledge_source`：`id`、`source_code`、`source_name`、`source_type`(A–E)、`authority_level`(1–5)、`url`、`license_note`、`retrieved_at`、`version`、`created_at`；唯一键 `(source_code, version)`。
- `agent_knowledge_chunk` 增加列 `source_code VARCHAR(64) NULL`、`authority_level TINYINT NULL`；**不改动既有唯一键**（新增索引 `idx_agent_chunk_source_code`）。

### 23.3 ingest 管线 `KnowledgeIngestService`

```
读取源（本地 JSON/CSV/导入文件）
  → 规范化（字段映射、编码统一、去重）
  → 切块（复用 KnowledgeChunker：500 字 / overlap 80）
  → 出处登记（无 source_name 或 version 的条目【直接拒绝入库】）
  → 向量化（Flask /embed；失败则退化为 BM25-only 并在 trace 标记降级）
  → 幂等写入（按 content_hash 判重，重复运行不产生重复块）
```

### 23.4 纪律（写入材料附录）

1. **禁止**爬取有版权限制的全文；只入库"可公开引用的摘要/结构化条目 + 出处链接"。
2. 每条知识块必须能在界面回溯到 `source_name` + URL；无出处条目不得入库。
3. 语料规模、来源构成与授权说明必须如实写入技术方案附录；引用他人数据须标注来源。
4. 入库规模与来源分布以**脚本产出报告**为准（`docs/eval/corpus-report.md`），不得口头宣称。

### 23.5 工作量与取舍

| 项 | 人日 |
|---|---|
| 水肥土壤（§22.2） | 1.0 |
| 病虫害流行（§22.3） | 1.5 |
| 管理经济（§22.4） | 0.5 |
| ingest 管线与来源登记（§23） | 1.5 |
| **合计** | **4.5** |

**取舍**：为腾出这 4.5 人日，知识图谱可视化降级为 2D 关系图，前端对话面板与性能对比面板保持"最小可用但完整"（不做动效与主题美化）。
