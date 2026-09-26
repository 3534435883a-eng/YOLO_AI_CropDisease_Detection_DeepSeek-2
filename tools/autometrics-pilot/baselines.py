# -*- coding: utf-8 -*-
"""映射判定数据集的两条基线（AutoMetrics 试点，阶段一）。

跑两条基线，是为了在引入 AutoMetrics 之前先确定**要超越的分数线**，并且验证这个任务确实非平凡：

1. `literal` —— 字面匹配启发式：类别中文标签与知识库条目名互为子串（去掉作物前缀后）即判为对应。
   这正是 `docs/vision-class-kb-mapping.md` 记载的失败方法。**它必须在此数据集上出错**，
   否则说明数据集没有区分度，试点结论就没有意义。
2. `llm` —— 单条 LLM 判据（LLM-as-judge），问模型给出 0-1 的把握分。
   这是论文声称要比它高 +33.4% Kendall τ 的基线。

用法::

    python baselines.py --baseline literal
    python baselines.py --baseline llm --model qwen-turbo

LLM 回答会缓存到 `llm_cache.json`，重跑不再产生费用。

**边界**：本脚本只做判定，不产生农艺结论。基线得分只说明"能否复现文档中的人工裁定"，
不说明"判据在农艺上正确"——文档本身的 25 条映射也只有开发者逐条核对，没有双人专家标注。
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATASET = HERE / "dataset.jsonl"
CACHE = HERE / "llm_cache.json"

DASHSCOPE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

# 两种判据提示词，用于区分"真实基线"与"泄题后的上限"。
#
# `neutral` 不提任何判别线索，是**诚实基线**：AutoMetrics 要超越的就是它。
# `hinted` 明确点出两条判别线索（名称相同未必同种、名称不同未必异种），
# 等于把本数据集上最具区分度的信息直接交给模型——仅作为上限对照保留，
# 不得用它冒充基线。第一次运行曾误用 hinted，得到 100% 准确率而掩盖了这一点。
JUDGE_NEUTRAL = (
    "你是植物病害名称核对员。你会看到一个视觉模型的类别标签和一个候选知识库条目。"
    "请判断两者是否指同一种病害或虫害。"
    "先给一行判断理由，最后单独一行输出 `SCORE: <0到1的小数>`，1 表示确定是同一种，0 表示确定不是。"
)

JUDGE_HINTED = (
    "你是植物病害名称核对员。你会看到一个视觉模型的类别标签（可能中英文不一致）和一个候选知识库条目。"
    "判断两者是否指同一种病害或虫害。注意：名称相同不代表同一种（英文标签可能揭示中文标注有误），"
    "名称不同也可能指同一种（如稻瘟病的别称、病原学名与中文病名的对应）。"
    "先给一行判断理由，最后单独一行输出 `SCORE: <0到1的小数>`，1 表示确定是同一种，0 表示确定不是。"
)

PROMPTS = {"neutral": JUDGE_NEUTRAL, "hinted": JUDGE_HINTED}

CROP_PREFIXES = ["苹果", "玉米", "马铃薯", "水稻", "草莓", "番茄", "小麦", "葡萄", "棉花", "黄瓜", "辣椒"]


def load_dataset():
    with DATASET.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def strip_crop_prefix(name):
    for prefix in CROP_PREFIXES:
        if name.startswith(prefix) and len(name) > len(prefix):
            return name[len(prefix):]
    return name


# --------------------------------------------------------------------------
# 基线一：字面匹配启发式
# --------------------------------------------------------------------------
def literal_name_score(record):
    """变体一：只比**条目名**的字面重合度 → 0/1。"""
    label = record["class_label_zh"]
    kb_name = record["kb_entry_name"]
    if label in kb_name or kb_name in label:
        return 1.0
    if strip_crop_prefix(label) == strip_crop_prefix(kb_name):
        return 1.0
    # 双向互相包含（如"白粉病果" vs "草莓白粉病果"），同样归为字面命中
    if strip_crop_prefix(label) in strip_crop_prefix(kb_name):
        return 1.0
    return 0.0


def label_stem(label):
    """去掉类别标签尾部的"病/虫/害"等类名后缀，取词干。

    文档 §一 记录的首版误判依据写的是"原文出现'卷叶'/'萎蔫'/'褐斑'"——
    即首版比对的是**词干**而非完整标签，否则"地面枯卷叶"这类巧合不会命中。
    """
    stem = strip_crop_prefix(label)
    for suffix in ("病害", "虫病", "病", "虫"):
        if stem.endswith(suffix) and len(stem) > len(suffix) + 1:
            return stem[: -len(suffix)]
    return stem


def literal_text_score(record):
    """变体二：**如实复现文档记载的首版失败方法**。

    判据 = 类别标签词干是否出现在知识库条目的全文（名称+症状+诱因+防治）中。
    文档明确指出这个判据不可采信：会产生反向证据（"不同于…枯萎病"）与巧合同串（"地面枯卷叶""紫褐斑"）。
    """
    stem = label_stem(record["class_label_zh"])
    if not stem:
        return 0.0
    return 1.0 if stem in record.get("kb_text_full", "") else 0.0


# --------------------------------------------------------------------------
# 基线二：单条 LLM 判据
# --------------------------------------------------------------------------
def load_cache():
    if CACHE.is_file():
        return json.loads(CACHE.read_text(encoding="utf-8"))
    return {}


def save_cache(cache):
    CACHE.write_text(json.dumps(cache, ensure_ascii=False, indent=1), encoding="utf-8")


def cache_key(model, system, user):
    digest = hashlib.sha256()
    for part in (model, system, user):
        digest.update(part.encode("utf-8"))
        digest.update(b"\x00")
    return digest.hexdigest()[:32]


def call_dashscope(model, system, user, api_key, retries=3):
    payload = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "temperature": 0.0,
        "max_tokens": 300,
    }).encode("utf-8")
    request = urllib.request.Request(
        DASHSCOPE_URL, data=payload, method="POST",
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + api_key},
    )
    last_error = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                body = json.loads(response.read().decode("utf-8"))
            return body["choices"][0]["message"]["content"]
        except (urllib.error.URLError, KeyError, IndexError, json.JSONDecodeError) as error:
            last_error = error
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError("DashScope 调用失败：{}".format(last_error))


def parse_score(text):
    """从模型回答里抽 `SCORE: x`；抽不到则返回 None 并把原文留给调用方记录。"""
    if not text:
        return None
    matches = re.findall(r"SCORE\s*[:：]\s*([01](?:\.\d+)?)", text, re.IGNORECASE)
    if matches:
        return max(0.0, min(1.0, float(matches[-1])))
    numbers = re.findall(r"\b([01](?:\.\d+)?)\b", text)
    if numbers:
        return max(0.0, min(1.0, float(numbers[-1])))
    return None


def llm_scores(records, model, api_key, system):
    cache = load_cache()
    scores, raw = [], {}
    for record in records:
        key = cache_key(model, system, record["input"])
        if key not in cache:
            cache[key] = call_dashscope(model, system, record["input"], api_key)
            save_cache(cache)
        answer = cache[key]
        raw[record["id"]] = answer
        scores.append(parse_score(answer))
    return scores, raw


# --------------------------------------------------------------------------
# 相关系数（手写实现，避免给项目环境加 scipy 依赖）
# --------------------------------------------------------------------------
def ranks(values):
    """平均秩，用于处理并列值。"""
    order = sorted(range(len(values)), key=lambda i: values[i])
    result = [0.0] * len(values)
    index = 0
    while index < len(order):
        end = index
        while end + 1 < len(order) and values[order[end + 1]] == values[order[index]]:
            end += 1
        average = (index + end) / 2.0 + 1.0
        for position in range(index, end + 1):
            result[order[position]] = average
        index = end + 1
    return result


def pearson(left, right):
    n = len(left)
    if n < 2:
        return 0.0
    mean_left = sum(left) / n
    mean_right = sum(right) / n
    numerator = sum((a - mean_left) * (b - mean_right) for a, b in zip(left, right))
    var_left = sum((a - mean_left) ** 2 for a in left)
    var_right = sum((b - mean_right) ** 2 for b in right)
    if var_left <= 0.0 or var_right <= 0.0:
        return 0.0
    return numerator / (var_left ** 0.5 * var_right ** 0.5)


def spearman(left, right):
    return pearson(ranks(left), ranks(right))


def kendall_tau_b(left, right):
    """Kendall τ-b：对并列值做分母校正。"""
    n = len(left)
    if n < 2:
        return 0.0
    concordant = discordant = tie_left = tie_right = 0
    for i in range(n):
        for j in range(i + 1, n):
            delta_left = left[i] - left[j]
            delta_right = right[i] - right[j]
            if delta_left == 0 and delta_right == 0:
                tie_left += 1
                tie_right += 1
            elif delta_left == 0:
                tie_left += 1
            elif delta_right == 0:
                tie_right += 1
            elif (delta_left > 0) == (delta_right > 0):
                concordant += 1
            else:
                discordant += 1
    total = 0.5 * n * (n - 1)
    denominator = ((total - tie_left) * (total - tie_right)) ** 0.5
    if denominator <= 0.0:
        return 0.0
    return (concordant - discordant) / denominator


def evaluate(name, records, scores, raw=None):
    labels = [r["score"] for r in records]
    unparsed = [r["id"] for r, s in zip(records, scores) if s is None]
    usable = [(r, s) for r, s in zip(records, scores) if s is not None]
    if unparsed:
        print("  警告：{} 条回答无法解析出分数，已从指标中剔除：{}".format(len(unparsed), unparsed))
    if len(usable) < 2:
        print("[{}] 有效样本不足，跳过".format(name))
        return
    pair_records = [r for r, _ in usable]
    pair_scores = [s for _, s in usable]
    pair_labels = [r["score"] for r in pair_records]

    # 以 0.5 为判定阈值算分类指标；相关系数直接用连续分数。
    predicted = [1 if s >= 0.5 else 0 for s in pair_scores]
    correct = sum(1 for p, y in zip(predicted, pair_labels) if p == y)
    tp = sum(1 for p, y in zip(predicted, pair_labels) if p == 1 and y == 1)
    tn = sum(1 for p, y in zip(predicted, pair_labels) if p == 0 and y == 0)
    fp = sum(1 for p, y in zip(predicted, pair_labels) if p == 1 and y == 0)
    fn = sum(1 for p, y in zip(predicted, pair_labels) if p == 0 and y == 1)
    sensitivity = tp / (tp + fn) if (tp + fn) else 0.0
    specificity = tn / (tn + fp) if (tn + fp) else 0.0

    print("[{}] n={}  准确率 {:.1%}  平衡准确率 {:.1%}  Spearman ρ {:.3f}  Kendall τ-b {:.3f}".format(
        name, len(pair_records), correct / len(pair_records),
        (sensitivity + specificity) / 2.0,
        spearman(pair_scores, pair_labels), kendall_tau_b(pair_scores, pair_labels)))
    print("       正例召回 {}/{}  负例召回 {}/{}".format(tp, tp + fn, tn, tn + fp))

    wrong = [(r, s) for r, s in zip(pair_records, pair_scores)
             if (1 if s >= 0.5 else 0) != r["score"]]
    if wrong:
        print("       判错的样本（人工裁定 ≠ 判据输出）：")
        for record, score in wrong:
            print("         {} [{}] {} / {} → 知识库「{}」  人工={}  判据={:.2f}".format(
                record["id"], record["label_rule"], record["class_label_en"],
                record["class_label_zh"], record["kb_entry_name"], record["score"], score))
    if raw is not None:
        print("       LLM 原始回答已缓存到 {}".format(CACHE.name))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline",
                        choices=["literal_name", "literal_text", "literal", "llm", "all"],
                        default="all")
    parser.add_argument("--model", default="qwen-turbo")
    parser.add_argument("--prompt", choices=["neutral", "hinted", "both"], default="both",
                        help="neutral 是诚实基线；hinted 泄题，仅作上限对照")
    args = parser.parse_args()

    if not DATASET.is_file():
        sys.exit("缺少数据集，请先运行 build_dataset.py")
    records = load_dataset()
    print("数据集 {} 条（正 {} / 负 {}）".format(
        len(records), sum(1 for r in records if r["score"] == 1),
        sum(1 for r in records if r["score"] == 0)))
    print()

    want_literal = args.baseline in ("literal_name", "literal_text", "literal", "all")
    if want_literal:
        evaluate("基线A1 字面-仅条目名", records, [literal_name_score(r) for r in records])
        print()
        evaluate("基线A2 字面-词干×全文（文档记载的首版方法）",
                 records, [literal_text_score(r) for r in records])
        print()

    if args.baseline in ("llm", "all"):
        api_key = os.environ.get("DASHSCOPE_API_KEY")
        if not api_key:
            sys.exit("未设置 DASHSCOPE_API_KEY，无法运行 LLM 基线")
        variants = ["neutral", "hinted"] if args.prompt == "both" else [args.prompt]
        for variant in variants:
            scores, raw = llm_scores(records, args.model, api_key, PROMPTS[variant])
            label = "基线B LLM判据({}, {}提示)".format(args.model, variant)
            if variant == "hinted":
                label += "  ← 泄题，非基线"
            evaluate(label, records, scores, raw)
            print()


if __name__ == "__main__":
    main()
