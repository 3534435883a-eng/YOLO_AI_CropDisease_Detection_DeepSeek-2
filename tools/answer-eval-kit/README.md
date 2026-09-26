# 回答轴评测工具链

用途：把"请农技人员给智能体回答打分"这件事变成可执行的流程，产出**独立人工评分**，
供 AutoMetrics 之类的自动评估器拟合与验证。

## 为什么需要这套东西

**项目任何地方都没有保存智能体回答**（2026-09-26 核实）：

| 位置 | 实际情况 |
|---|---|
| `AgentChatController` | SSE 流式返回，**不落库** |
| `SessionHistoryStore` | 进程内内存，重启即失 |
| `agent_step_trace` 表 | 只存 `input_digest` / `output_digest` 审计摘要，**不含正文** |

所以"给回答打分"缺的第一段就是**把回答抓下来**。这就是本工具链存在的理由。

## 流程

```
capture_answers.py     →  answers.jsonl   （抓取，需平台在跑 + DEEPSEEK_API_KEY）
rater.html             →  labels.jsonl    （评分，离线单文件，双击即可用）
to_autometrics.py --check → autometrics_dataset.jsonl （转换与校验，不需要 AutoMetrics）
to_autometrics.py --run   → 拟合自动评估器（需放行 autometrics-ai）
```

抓取前置条件（脚本启动时会逐项检查并明确报错）：后端在 `http://localhost:9999` 运行、
已设置 `DEEPSEEK_API_KEY`（`application.properties:19` 读的就是它）、知识库已入库。

## 评分维度

四个维度**直接对应项目自己声明的回答契约**（`DeepSeekLlmClient.COMPOSE_HINT` 与 `GuardrailService` 的要求），
而不是另发明一套标准——这样"评分低"能直接指回项目承诺过的义务：

| 维度 | 对应项目里的哪条要求 |
|---|---|
| D1 依据支持度 | 正文陈述须被 `CitationFormatter` 给出的证据支持 |
| D2 引用可核对性 | `CitationReferenceValidator` 校验的引用编号有效性 |
| D3 不确定性表达 | COMPOSE_HINT"证据不足时明确说明依据不足，不得臆造" |
| D4 安全与越权边界 | COMPOSE_HINT"涉及药剂必须提示遵循当地登记与用药规范并说明需人工确认"；`GuardrailService` 拦截越权执行声明 |
| **D5 总体可采信度** | **拟合目标**：农户照着这条回答去田里会不会被误导 |

## 两个刻意的设计决定

1. **默认折叠"开发者预期"。** 题目集里记了开发者自己的预期答案。先看它会让评分向它靠拢，
   得到的就不再是独立标注，拟合出的评估器也失去意义。评分器默认折叠，并写明"建议评分后再看"。
2. **d1–d4 不进入 AutoMetrics 的 `metric_columns`。** 它们是人工评分，当作候选指标喂给回归
   会虚高拟合效果，也偏离论文"从 LLM 生成的判据与指标库中挑选"的做法。
   它们仍写进输出文件，供事后分析（例如检查四个维度与总体分是否自洽）。

## 分区纪律

- `partition=dev`（当前 16 题）→ 用于拟合
- `partition=holdout`（当前 4 题，来自 `docs/eval/developer_holdout.jsonl`）→ **只用于最终评估**，
  不得用于调参或拟合

`to_autometrics.py` 会自动按此切分，并在样本量不足时打印警告。

## 引用取自哪一帧（踩过的坑，别再改回去）

**`final` 帧不带引用列表。** 后端 `AgentOrchestrator.finish` 的 final 载荷只有
`status / reason / citationCount / steps`；引用只出现在 `step` 帧，且 `step` 帧里有两份：
`stepCitations`（本步新增）与 `citations`（**全局合并、已重新编号**——模型在证据块里看到的就是这一份）。

前端不受影响（`agentChat/index.vue` 在 step 帧累加、只把 final 帧当覆盖项），
但本脚本初版只读 final 帧，于是 2026-09-26 首次真实抓取的 20 条回答**全是 `citations: []`**，
评分人看不到证据，D1/D2 两个维度无从评起。现已改为三级回退
（`final` → `step_global` → `step_accumulated`），并把取自哪一级如实写进 `citationsSource` 字段。
`step_accumulated` 是异常路径（编号可能与正文不一致），出现时应在报告里标注。

## 已验证 / 未验证（截至 2026-09-26）

**已验证**：

- `capture_answers.py --selftest` → **10/10 通过**。用与后端 `finish()` 结构一致的合成 SSE 流
  验证解析（回答正文取自 `message`、引用取自 `data.citations`、拒答原因取自 `data.reason`、
  DONE/REFUSED/ERROR 三态、CRLF 与不完整 JSON 容错）。
- **真实抓取已跑通**（2026-09-26，DeepSeek 真实调用 + Flask 向量服务在线，非降级）：
  20 题全部返回，19 DONE / 1 REFUSED，无传输错误。结果在 `answers.jsonl`。
  ⚠️ 该文件抓于**引用回退修复之前**，其 `citations` 字段为空，是修复前状态的基线，不要当证据清单用。
- `probe_artifacts.py`（本目录新增）：赛题点名的四类交付物 + 两类输入方式的 8 条补充探针，
  结果在 `artifacts.jsonl`。`questions.jsonl` 的 20 题**绝大多数是病虫害诊断**，
  覆盖不到农事管理方案 / 水肥处方 / 生产规划报告，故补此脚本。
- `to_autometrics.py --check` 用 `fixtures/` 跑通：4 条答案 3 条评分 → 3 条可用、1 条跳过、
  分区切分正确、字段映射正确（`input` 含问题+证据，`output` 为回答，`score` 为 d5）。

**未验证（不要当成已就绪）**：

- **`--run` 从未运行过**：`autometrics-ai` 的运行权限未放行。
- 题目集 v1 共 20 题，是**骨架不是成品**。要让拟合结果可解释，`dev` 需要约 50–80 条已评分回答；
  扩充时必须追加版本号并记录变更原因（`docs/eval/README.md`）。
- **仍没有农技人员评分**：`answers.jsonl` / `artifacts.jsonl` 只有回答本身，
  `labels.jsonl` 为空。"回答质量"目前只有本目录作者的人工阅读结论，不构成专家标注。

## 边界

- **评分人必须是农技人员，标注才有意义。** 开发者自评只能得到"开发者意见"，不是专家标注；
  项目文档本身已声明"没有农技专家双人标注"，本工具链不改变这一点。
- **评分全程离线。** `rater.html` 是本地文件，评分只存在浏览器 localStorage 与导出的 `labels.jsonl`，
  不上传任何地方。只有 `to_autometrics.py --run` 会把回答与证据发往 DashScope 端点。
- 抓取到的回答是**平台在特定版本下、特定知识库状态下的产物**。报告引用时必须同时给出
  代码版本、知识库版本与抓取时间，否则数字无法复现。
