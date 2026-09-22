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

向量数据库、图数据库、多智能体协作、模型微调、实时传感器接入、真实执行器控制、自动用药、通用领域问答、用户级配额与计费。

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
| W3 | 视觉指标取证、Kaggle/公开集验证、材料配套 | 与材料生产并行 |

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
