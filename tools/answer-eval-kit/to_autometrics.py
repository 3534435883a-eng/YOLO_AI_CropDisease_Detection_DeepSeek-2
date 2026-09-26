# -*- coding: utf-8 -*-
"""把「回答 + 人工评分」转成 AutoMetrics 数据集，并可选择运行 AutoMetrics（回答轴评测，第 3 步）。

**字段映射与理由**：

| AutoMetrics 字段 | 本工具填入 | 为什么 |
|---|---|---|
| `input` | 问题 + 作物 + 该回答引用的**证据列表** | 判据要判断"回答是否被证据支持"，就必须看得见证据。证据属于上下文，不属被判对象。 |
| `output` | 回答正文 | 被判对象。 |
| `target_measure` | `d5` 总体可采信度 | 人工独立判断，是本批标注的核心目标。 |

**刻意不做的一件事**：d1–d4（依据支持度、引用可核对性、不确定性、安全边界）**不放进 `metric_columns`**。
它们是人工评分，放进去等于把人工标注当成候选指标喂给回归，会虚高拟合效果、
也偏离论文"从 LLM 生成的判据与指标库里挑选"的做法。它们仍会写进输出文件供事后分析。

**分区纪律**：`partition=dev` 用于拟合，`partition=holdout` **只用于最终评估**，不得用于调参。
见 `docs/eval/README.md`。

用法::

    python to_autometrics.py --check                 # 只校验与转换，不导入 autometrics
    python to_autometrics.py --run --model qwen-turbo  # 实际运行 AutoMetrics（需先放行）
"""
import argparse
import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ANSWERS = HERE / "answers.jsonl"
LABELS = HERE / "labels.jsonl"
OUT_DATASET = HERE / "autometrics_dataset.jsonl"

DASH_SCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"


def load_jsonl(path):
    if not path.is_file():
        return []
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def describe_citation(citation):
    parts = []
    for key in ("index", "name", "title", "diseaseName", "sourceName", "cropType", "fieldType", "url"):
        if citation.get(key) is not None:
            parts.append("{}={}".format(key, str(citation[key])[:120]))
    return " | ".join(parts) if parts else json.dumps(citation, ensure_ascii=False)[:200]


def build_input(record):
    citations = record.get("citations") or []
    evidence = "\n".join("  [{}] {}".format(i + 1, describe_citation(c)) for i, c in enumerate(citations))
    return (
        "作物：{crop}\n"
        "农户问题：{question}\n"
        "该回答引用的依据：\n{evidence}\n"
        "请评估这条回答在农技上是否可采信。"
    ).format(crop=record.get("crop") or "未标注",
             question=record["question"],
             evidence=evidence if evidence else "  （无引用，回答可能走的是拒答路径）")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="只校验与转换")
    parser.add_argument("--run", action="store_true", help="运行 AutoMetrics")
    parser.add_argument("--model", default="qwen-turbo", help="判据 LLM（DashScope 上可用）")
    parser.add_argument("--generated-only", action="store_true", default=True,
                        help="≤100 条数据走 generated-only，跳过 48 指标库（不需要 Java/pyserini）")
    parser.add_argument("--answers", default=str(ANSWERS), help="回答文件（可指向样本文件做校验）")
    parser.add_argument("--labels", default=str(LABELS), help="评分文件")
    parser.add_argument("--out", default=str(OUT_DATASET), help="输出数据集路径")
    args = parser.parse_args()

    answers = load_jsonl(Path(args.answers))
    labels = load_jsonl(Path(args.labels))
    if not answers:
        sys.exit("缺少 {}——请先运行 capture_answers.py 抓取回答".format(ANSWERS.name))
    if not labels:
        sys.exit("缺少 {}——请先用 rater.html 评分并导出".format(LABELS.name))

    label_by_id = {row["id"]: row for row in labels}
    rows, skipped = [], []
    for record in answers:
        label = label_by_id.get(record["id"])
        if label is None or label.get("skipped"):
            skipped.append(record["id"])
            continue
        if "d5" not in label:
            skipped.append(record["id"])
            continue
        rows.append({
            "id": record["id"],
            "partition": record.get("partition"),
            "input": build_input(record),
            "output": record.get("answer") or "",
            "score": label["d5"],
            "status": record.get("status"),
            "refusalReason": record.get("refusalReason"),
            "human_d1": label.get("d1"),
            "human_d2": label.get("d2"),
            "human_d3": label.get("d3"),
            "human_d4": label.get("d4"),
            "note": label.get("note", ""),
        })

    dev = [r for r in rows if r["partition"] != "holdout"]
    holdout = [r for r in rows if r["partition"] == "holdout"]

    out_path = Path(args.out)
    with out_path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")

    print("答案 {} 条，评分 {} 条 → 可用 {} 条 → {}".format(
        len(answers), len(labels), len(rows), out_path.name))
    print("  拟合集（dev）{} 条 / 最终评估集（holdout）{} 条".format(len(dev), len(holdout)))
    if skipped:
        print("  跳过 {} 条（未评分或标记无法判断）：{}".format(len(skipped), skipped[:10]))

    warnings = []
    if len(dev) < 30:
        warnings.append("dev 只有 {} 条：AutoMetrics 要在这上面拟合 PLS 权重，样本太少会让结果不可解释".format(len(dev)))
    if len(holdout) < 10:
        warnings.append("holdout 只有 {} 条：不足以判断归纳出的评估器是否泛化".format(len(holdout)))
    if len({r["score"] for r in rows}) < 3:
        warnings.append("总体可采信度只有 {} 种取值，回归目标几乎无方差".format(len({r["score"] for r in rows})))
    if warnings:
        print("\n⚠ 样本量警告（这些不是小问题，直接影响结论能不能用）：")
        for item in warnings:
            print("  - " + item)

    if args.check or not args.run:
        print("\n已转换。要运行 AutoMetrics 加 --run（需放行 autometrics-ai 的运行权限）。")
        return 0

    # ---- 以下需要运行 autometrics-ai ----
    if not os.environ.get("DASHSCOPE_API_KEY"):
        sys.exit("未设置 DASHSCOPE_API_KEY")
    if not dev or not holdout:
        sys.exit("拟合集或评估集为空，无法运行")

    try:
        import pandas as pd
        import dspy
        from autometrics.autometrics import Autometrics
        from autometrics.dataset.Dataset import Dataset
    except ImportError as error:
        sys.exit("无法导入 AutoMetrics：{}。请确认已在其独立 venv 中运行本脚本。".format(error))

    llm = dspy.LM("dashscope/" + args.model, api_base=DASH_SCOPE_BASE)
    frame = pd.DataFrame([{"id": r["id"], "input": r["input"], "output": r["output"], "score": r["score"]}
                          for r in dev])
    dataset = Dataset(dataframe=frame, name="TomatoAgentAnswerQuality",
                      data_id_column="id", input_column="input", output_column="output",
                      target_columns=["score"], ignore_columns=["id"],
                      metric_columns=[], reference_columns=[],
                      task_description="评估番茄种植智能体回答在农艺上是否可采信。")
    results = Autometrics().run(dataset=dataset, target_measure="score",
                               generator_llm=llm, judge_llm=llm)
    induced = results["regression_metric"]
    print("\n归纳出的评估器已在 dev 上拟合。")
    print("注意：其质量必须由 holdout 上的表现来判断，本脚本不替你下结论——")
    print("      请把下面的预测值交回人工核对，或扩展 holdout 后另行评估。")
    for row in holdout:
        print("  {} 人工={} ".format(row["id"], row["score"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
