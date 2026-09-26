# -*- coding: utf-8 -*-
"""赛题四类交付物 + 两类输入方式的补充探针（回答轴评测工具链的旁路）。

`questions.jsonl` 的 20 题**绝大多数是病虫害诊断**，覆盖不到赛题点名要求的另外三类交付物
（个性化农事管理方案、水肥调控处方、生产规划报告），也不覆盖"上传农情数据"这条输入路径。
本脚本只补这几条，不替换 `capture_answers.py`。

用法::

    python probe_artifacts.py --base http://localhost:9999
    python probe_artifacts.py --base http://localhost:9999 --force

结果写入 `artifacts.jsonl`。每条记录带 `expect` 字段，写明**按赛题该交付什么**，
便于人工逐条对照，而不是只看"有没有报错"。
"""
import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from capture_answers import citations_for_record, extract_result, load_jsonl, request_stream  # noqa: E402

HERE = Path(__file__).resolve().parent
OUT = HERE / "artifacts.jsonl"

# 每条：id / 赛题对应项 / crop / question / expect（该交付什么才算符合赛题）
PROBES = [
    {
        "id": "art-farm-plan",
        "brief": "个性化农事管理方案",
        "crop": "番茄",
        "question": "我这棚番茄刚定植一周，接下来这一周具体该做哪些农事管理？按天给我排一下。",
        "expect": "给出**可照做**的分天农事安排（温度/水肥/整枝/通风等），且明确哪些是推演建议、需人工确认；不得声称已执行设备动作。",
    },
    {
        "id": "art-pest-control",
        "brief": "病虫害防治建议",
        "crop": "番茄",
        "question": "棚里湿度连着几天 90% 以上，番茄叶背出现白霉，该怎么防治？",
        "expect": "给出有知识库依据的防治建议，标注 [编号] 引用；涉及药剂须提示遵循当地登记与用药规范、需人工确认。",
    },
    {
        "id": "art-water-fertilizer",
        "brief": "水肥调控处方",
        "crop": "番茄",
        "question": "番茄第一穗果开始膨大了，现在该怎么浇水、追什么肥、各用多少量？",
        "expect": "给出**带量的**水肥处方（灌溉下限/单次水量、追肥品种与用量、施用节点），或明确说明依据不足；给量必须有出处。",
    },
    {
        "id": "art-production-report",
        "brief": "生产规划报告",
        "crop": "番茄",
        "question": "给我出一份这季番茄的生产规划报告，该选哪套管理方案？",
        "expect": "输出报告体（方案对比、推荐方案与依据、风险提示、数据可信度声明），且标明是场景推演草案、非现场实测。",
    },
    {
        "id": "art-input-env",
        "brief": "输入：农情数据（当地实时/模拟）",
        "crop": "番茄",
        "question": "现在当地什么天气？这种天气适不适合给番茄通风？",
        "expect": "明确区分实时观测与演示用模拟地；模拟数据不得当观测结论用，应如实说明依据不足。",
    },
    {
        "id": "art-input-greenhouse",
        "brief": "输入：上传农情数据（棚内状态）",
        "crop": "番茄",
        "question": "我棚里现在温湿度多少？风险高不高？",
        "expect": "读出推演状态（环境指标/设备/告警），并声明这是场景推演不是现场传感器实测。",
    },
    {
        "id": "art-input-vision",
        "brief": "输入：上传农情数据（识别结果）",
        "crop": "番茄",
        "question": "摄像头识别结果是 Early_Blight（早疫病），这是什么病？现在该怎么办？",
        "expect": "把视觉类别映射到知识库条目并给出依据；若知识库无对应条目应直说，不得猜病名。",
    },
    {
        "id": "art-boundary-offtopic",
        "brief": "边界：应拒答",
        "crop": "番茄",
        "question": "帮我写一首关于春天的诗。",
        "expect": "拒答，且理由具体（不属于农业知识范围 / 无可靠依据）。",
    },
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:9999")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--sleep", type=float, default=1.0)
    args = parser.parse_args()

    if not os.environ.get("DEEPSEEK_API_KEY"):
        print("未设置 DEEPSEEK_API_KEY——后端读的正是这个变量（application.properties:19）", file=sys.stderr)
        return 2

    done = {row.get("id") for row in load_jsonl(OUT)}
    rows = []
    for probe in PROBES:
        if probe["id"] in done and not args.force:
            print(f"skip  {probe['id']}")
            continue
        started = time.time()
        try:
            text = request_stream(args.base, probe["question"], probe["crop"], "probe-" + probe["id"])
            parsed = extract_result(__import__("capture_answers").parse_sse_stream(text))
            error = None
        except Exception as exc:  # noqa: BLE001 - 记录后继续，不让一条失败中断整批
            parsed, error = {"answer": None, "citations": [], "reason": None, "steps": None,
                             "status": "ERROR", "step_citations": [], "tool_trace": [],
                             "event_types": []}, f"{type(exc).__name__}: {exc}"
        row = dict(probe)
        row.update(parsed)
        # 与 capture_answers.py 同一套回退：final 帧不带 citations，引用只出现在 step 帧。
        row["citations"], row["citationsSource"] = citations_for_record(parsed)
        row["error"] = error
        row["elapsed_s"] = round(time.time() - started, 1)
        row["captured_at"] = datetime.now(timezone.utc).isoformat()
        rows.append(row)
        print(f"{row['status']:8s} {probe['id']:22s} steps={row['steps']} cites={len(row['citations'])} "
              f"tools={','.join(row['tool_trace']) or '-'} {row['elapsed_s']}s"
              + (f"  ERROR={error}" if error else ""))
        time.sleep(args.sleep)

    if rows:
        with OUT.open("a", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"\n已写入 {OUT.name}（本次 {len(rows)} 条）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
