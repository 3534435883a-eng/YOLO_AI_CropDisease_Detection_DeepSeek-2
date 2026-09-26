# -*- coding: utf-8 -*-
"""构建「视觉类别 → 知识库条目」映射判定数据集（AutoMetrics 试点，阶段一）。

**这个数据集回答什么**：给一个视觉模型的类别标签（英文标签 + 中文标签）和一个候选知识库条目，
判定两者是否指同一种病害/虫害。这是 `docs/vision-class-kb-mapping.md` 里人工逐条裁定的那个任务。

**为什么用它做试点**：这个任务的标签不带主观评分，但**也不可被字面匹配复现**——
文档记录的失败模式恰恰是"子串出现在原文里"就误判为对应（含反向证据、巧合同串）。
所以它是零标注成本、又非平凡的判定任务：字符串相似度会错，必须读懂语义与中英文标签差异。

**标签来源与边界**：
- 正例来自 `docs/vision-class-kb-mapping.md` 已裁定并写入映射的类别，每条带文档给出的依据类型。
- 负例只取文档**确实做出过实质裁定**的两类：§三"被否决的候选"（4 条），
  以及 §二 ⚠️ 中"已撤回映射"的 2 条（英文标签与中文标签不一致）。
- §四"无任何线索（22 条）"**不纳入**：那些条目的标签是"知识库现状找不到依据"，
  而不是"不存在对应关系"。把它们当负例会把"标注者没找到"和"确实不对应"混为一谈，
  并会把 LLM 可能发现的**真实漏配**记成判据错误。留待阶段二用检索候选单独处理。

**自检**：脚本会校验每个引用的知识库条目名真实存在于知识库源中，对不上直接报错退出——
不允许静默产出一条指向不存在条目的样本。
"""
import json
import re
import sys
import unicodedata
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SQL_PATH = REPO / "cropdisease.sql"
CURATED_DIR = (REPO / "YOLO_AI_CropDisease_Detection_SpringBoot"
               / "src" / "main" / "resources" / "knowledge")
MAPPING_DOC = REPO / "docs" / "vision-class-kb-mapping.md"
OUT_PATH = Path(__file__).resolve().parent / "dataset.jsonl"

# 判定用文本的截断长度：症状原文最长逾千字（如玉米螟），截断只为控制 token 成本。
SYMPTOM_LIMIT = 1200


def strip_invisible(text):
    """清掉零宽空格等不可见字符——文档 §五 记录了 U+200B 会破坏字符串比较。

    入参可能是数字（如 curated JSON 的 authorityLevel 是整数），统一转字符串。
    """
    if text is None:
        return ""
    cleaned = "".join(ch for ch in str(text) if unicodedata.category(ch) != "Cf")
    return re.sub(r"\s+", " ", cleaned).strip()


def parse_sql_disease_rows(sql_text):
    """解析 `INSERT INTO `disease` VALUES (...)` 行，返回 (name -> entry) 映射。

    SQL dump 里的字符串以单引号包裹，内部可能含 `\\'` 转义、`?` 占位与换行。
    这里用一个最小的状态机逐字符扫描，避免正则被内容里的括号与引号带偏。
    """
    entries = {}
    pattern = re.compile(r"INSERT INTO `disease` VALUES \((.*?)\);\s*$", re.MULTILINE | re.DOTALL)
    for match in pattern.finditer(sql_text):
        fields = split_sql_values(match.group(1))
        if len(fields) < 6:
            continue
        name = strip_invisible(fields[1])
        if not name:
            continue
        entries[name] = {
            "crop": strip_invisible(fields[2]),
            "symptom": strip_invisible(fields[3]),
            "cause": strip_invisible(fields[4]),
            "control": strip_invisible(fields[5]),
            "source": "cropdisease.sql disease 表（E 类项目历史数据）",
        }
    return entries


def split_sql_values(body):
    """把 `(a, b, c)` 的内部拆成字段列表，正确处理单引号字符串与 `\\'` 转义。"""
    fields = []
    buffer = []
    in_string = False
    index = 0
    while index < len(body):
        char = body[index]
        if in_string:
            if char == "\\" and index + 1 < len(body):
                buffer.append(body[index + 1])
                index += 2
                continue
            if char == "'":
                in_string = False
            else:
                buffer.append(char)
        else:
            if char == "'":
                in_string = True
            elif char == ",":
                fields.append("".join(buffer))
                buffer = []
            else:
                buffer.append(char)
        index += 1
    fields.append("".join(buffer))
    return [f.strip() for f in fields]


def load_curated_entries():
    """读入人工改写并登记来源的 B 类知识条目。"""
    entries = {}
    for path in sorted(CURATED_DIR.glob("curated-*.json")):
        payload = json.loads(path.read_text(encoding="utf-8"))
        for item in payload.get("entries", []):
            name = strip_invisible(item.get("name"))
            if not name:
                continue
            entries[name] = {
                "crop": strip_invisible(item.get("crop")),
                "symptom": strip_invisible(item.get("symptom")),
                "cause": strip_invisible(item.get("cause")),
                "control": strip_invisible(item.get("control")),
                "source": "{}（{}，{}）".format(
                    strip_invisible(item.get("sourceName")),
                    strip_invisible(item.get("sourceType")),
                    strip_invisible(item.get("authorityLevel"))),
            }
    return entries


# ---------------------------------------------------------------------------
# 标签表。每条都对应 docs/vision-class-kb-mapping.md 中的一处**实质裁定**。
# 字段：(作物, 英文标签, 中文标签, 知识库条目名, 标签, 依据类型, 文档位置)
# ---------------------------------------------------------------------------
POSITIVES = [
    # —— §二 名称层面（V1/V2/V3，18 条）
    ("apple", "CedarRust", "苹果锈病", "苹果锈病", "V1", "§二 名称完全一致"),
    ("corn", "Rust", "玉米锈病", "玉米锈病", "V1", "§二 名称完全一致"),
    ("potato", "Early_Blight", "早疫病", "马铃薯早疫病", "V3", "§二 名称包含"),
    ("potato", "Late_Blight", "晚疫病", "马铃薯晚疫病", "V3", "§二 名称包含"),
    ("rice", "Narrow_Br_Spot", "窄条斑病", "水稻窄条斑病", "V3", "§二 名称包含"),
    ("strawberry", "Angular_LS", "角斑病", "草莓角斑病", "V3", "§二 名称包含"),
    ("strawberry", "Anthracnose_FR", "炭疽果腐", "草莓炭疽果腐病", "V3", "§二 名称包含"),
    ("strawberry", "Blossom_BT", "花枯病", "草莓花枯病", "V3", "§二 名称包含"),
    ("strawberry", "Gray_Mold", "灰霉病", "草莓灰霉病", "V3", "§二 名称包含"),
    ("strawberry", "Leaf_Spot", "叶斑病", "草莓叶斑病", "V3", "§二 名称包含"),
    ("strawberry", "Powdery_Fruit", "白粉病果", "草莓白粉病果", "V2", "§二 去作物前缀后一致"),
    ("strawberry", "Powdery_Leaf", "白粉病叶", "草莓白粉病叶", "V2", "§二 去作物前缀后一致"),
    ("tomato", "Early_Blight", "早疫病", "番茄早疫病", "V3", "§二 名称包含"),
    ("tomato", "Late_Blight", "晚疫病", "番茄晚疫病", "V3", "§二 名称包含"),
    ("tomato", "Leaf_Mold", "叶霉病", "番茄叶霉病", "V3", "§二 名称包含"),
    ("wheat", "Leaf_Rust", "小麦叶锈病", "小麦叶锈病", "V1", "§二 名称完全一致"),
    ("wheat", "Powdery_Mildew", "小麦白粉病", "小麦白粉病", "V1", "§二 名称完全一致"),
    ("wheat", "Stripe_Rust", "小麦条锈病", "小麦条锈病", "V1", "§二 名称完全一致"),
    # —— §二 知识库原文枚举（2 条）
    ("rice", "Leaf_Blast", "叶瘟病", "稻瘟病", "KB_ENUM", "§二 原文把叶瘟列为稻瘟病的一种"),
    ("rice", "Neck_Blast", "穗颈瘟", "稻瘟病", "KB_ENUM", "§二 原文把穗颈瘟列为稻瘟病的一种"),
    # —— §二 外部权威来源（1 条）
    ("tomato", "Septoria", "壳针孢病", "番茄斑枯病", "EXTERNAL_AUTHORITY",
     "§二 权威来源记载 Septoria lycopersici 即番茄壳针孢"),
    # —— §六 2026-09-24 权威资料补充（3 条）
    ("tomato", "Leaf_Miner", "潜叶虫", "番茄潜叶蝇（潜叶虫）", "EXTERNAL_AUTHORITY",
     "§六 UC IPM 记载潜道；只映射害虫类群，不判定具体种"),
    ("tomato", "YLCV", "黄化卷叶病毒", "番茄黄化曲叶病", "EXTERNAL_AUTHORITY",
     "§六 UC IPM 记载病原与粉虱传播；图像不能确诊病毒"),
    ("tomato", "Spider_M", "红蜘蛛", "番茄叶螨（红蜘蛛类）", "EXTERNAL_AUTHORITY",
     "§六 UC IPM 记载叶螨失绿斑点与结网；不能确定具体螨种"),
    # —— §七 2026-09-24 病害知识扩充（1 条）
    ("apple", "Scab", "黑星病", "苹果黑星病", "EXTERNAL_AUTHORITY",
     "§七 UC IPM 苹果黑星病；据明确病名映射，图像不能单独确诊"),
]

NEGATIVES = [
    # —— §三 被否决的候选（表格实际 4 行；标题写 5 条，见文末核对说明）
    ("cotton", "Blight", "枯萎病", "棉花黑根腐病", "REJECTED_SUBSTRING",
     "§三 原文是「不同于棉花黄萎病与枯萎病」，属反向证据"),
    ("cotton", "Curl", "卷叶病", "棉大卷叶螟", "REJECTED_SUBSTRING",
     "§三 只是越冬描述里的「地面枯卷叶」巧合同串"),
    ("cotton", "Wilt", "萎蔫病", "棉花黑根腐病", "REJECTED_SUBSTRING",
     "§三 「叶片萎蔫」是症状词不是病名"),
    ("rice", "Brn_Spot", "褐斑病", "水稻紫鞘病", "REJECTED_SUBSTRING",
     "§三 原文是「紫褐斑」巧合同串"),
    # —— §二 ⚠️ 已撤回映射（2 条）
    ("rice", "Scald", "纹枯病", "水稻纹枯病", "WITHDRAWN_LABEL_CONFLICT",
     "§二 Scald 在英文文献中通常指水稻云形病/褐色叶枯病，与纹枯病不是同一病害"),
    ("grape", "Downey_Mildew", "白粉病", "葡萄白粉病", "WITHDRAWN_LABEL_CONFLICT",
     "§二 Downy Mildew 为霜霉病（Plasmopara viticola），非白粉病"),
]


def build_judge_input(crop, english_label, chinese_label, kb_name, kb_crop):
    """构造判定任务的提问文本：只给名称层面的证据，不泄露文档的判定规则。"""
    return (
        "作物：{crop}\n"
        "视觉模型类别标签：{english}（中文标注：{chinese}）\n"
        "候选知识库条目名称：{kb_name}（作物：{kb_crop}）\n"
        "问题：该视觉模型类别与这个知识库条目是否指同一种病害或虫害？"
        "请判断并给出 0-1 之间的把握分（1 = 确定是同一种，0 = 确定不是）。"
    ).format(crop=crop, english=english_label, chinese=chinese_label,
             kb_name=kb_name, kb_crop=kb_crop)


def build_kb_document(entry):
    """判定所依据的知识库条目正文（症状为主，附作物）。"""
    symptom = entry["symptom"]
    if len(symptom) > SYMPTOM_LIMIT:
        symptom = symptom[:SYMPTOM_LIMIT] + "…"
    return "作物：{crop}\n条目名称见题面。症状：{symptom}".format(
        crop=entry["crop"], symptom=symptom)


def build_kb_full_text(kb_name, entry):
    """条目全文（名称+症状+诱因+防治），**只供文字子串基线使用**。

    文档 §一 记载首版核验的失败方法是"子串出现在症状/诱因/防治文本中"就判定对应，
    因此要如实复现这个失败，基线必须能看到全文，而不是只看症状。
    这份全文不进 `output` 字段，以免把大段药剂清单塞进判定提示。
    """
    return "名称：{}\n作物：{}\n症状：{}\n诱因：{}\n防治：{}".format(
        kb_name, entry["crop"], entry["symptom"], entry["cause"], entry["control"])


def main():
    if not SQL_PATH.is_file():
        sys.exit("缺少知识库源：{}".format(SQL_PATH))
    if not MAPPING_DOC.is_file():
        sys.exit("缺少映射文档：{}".format(MAPPING_DOC))

    kb = load_curated_entries()
    curated_count = len(kb)
    kb.update(parse_sql_disease_rows(SQL_PATH.read_text(encoding="utf-8")))
    print("知识库条目：curated {} 条 + SQL 共 {} 条".format(curated_count, len(kb)))

    records = []
    missing = []
    for index, (crop, english, chinese, kb_name, rule, cite) in enumerate(POSITIVES, start=1):
        record = make_record("pos-%03d" % index, crop, english, chinese, kb_name, 1, rule, cite, kb, missing)
        if record:
            records.append(record)
    for index, (crop, english, chinese, kb_name, rule, cite) in enumerate(NEGATIVES, start=1):
        record = make_record("neg-%03d" % index, crop, english, chinese, kb_name, 0, rule, cite, kb, missing)
        if record:
            records.append(record)

    if missing:
        # 宁可构建失败，也不要产出一条指向不存在知识库条目的样本——那会让标签失去依据。
        sys.exit("以下知识库条目名在知识库源中不存在，请核对文档或条目名：\n  " + "\n  ".join(missing))

    with OUT_PATH.open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")

    positives = sum(1 for r in records if r["score"] == 1)
    negatives = len(records) - positives
    print("已写出 {} 条 → {}".format(len(records), OUT_PATH))
    print("  正例 {}（已裁定映射）/ 负例 {}（已裁定不对应）".format(positives, negatives))
    print("  负例构成：被否决的子串巧合同串 4 条 + 中英文标签冲突已撤回 2 条")
    print("  诊断价值最高的是 neg-005/neg-006：名称完全相同但英文标签揭示中文标注错误，")
    print("  纯字符串匹配必然答错，只有读懂中英文标签差异才能答对。")


def make_record(sample_id, crop, english, chinese, kb_name, score, rule, cite, kb, missing):
    entry = kb.get(kb_name)
    if entry is None:
        missing.append("{} / {} → 「{}」".format(crop, english, kb_name))
        return None
    return {
        "id": sample_id,
        "crop": crop,
        "class_label_en": english,
        "class_label_zh": chinese,
        "kb_entry_name": kb_name,
        "kb_crop": entry["crop"],
        "kb_source": entry["source"],
        "input": build_judge_input(crop, english, chinese, kb_name, entry["crop"]),
        "output": build_kb_document(entry),
        "kb_text_full": build_kb_full_text(kb_name, entry),
        "score": score,
        "label_rule": rule,
        "label_cite": cite,
        "kb_entry_exists": True,
    }


if __name__ == "__main__":
    main()
