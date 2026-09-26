# -*- coding: utf-8 -*-
"""抓取智能体回答，形成可评分的回答集（回答轴评测工具链，第 1 步）。

**为什么需要这一步**：项目**任何地方都没有保存智能体回答**——
`AgentChatController` 走 SSE 流式返回且不落库，`SessionHistoryStore` 是内存的，
`agent_step_trace` 只存 `input_digest`/`output_digest` 审计摘要。
所以"请农技人员给回答打分"这件事，缺的第一段管线就是**把回答抓下来**。

**前置条件**（缺一不可，脚本会在启动时检查并明确报错）：
- Spring Boot 后端在运行（默认 `http://localhost:9999`）
- 已设置 `DEEPSEEK_API_KEY`（`application.properties:19` 读的就是它）
- 知识库已入库（否则证据为空，全部走拒答路径）

用法::

    python capture_answers.py --selftest            # 只验证 SSE 解析逻辑，不联网
    python capture_answers.py --base http://localhost:9999
    python capture_answers.py --partition dev       # 只抓 dev 分区

抓取结果写入 `answers.jsonl`，已抓过的 id 默认跳过（可 `--force` 重抓）。
`partition=holdout` 的题目**不得**用于调参或拟合，只用于最终评估——
见 `docs/eval/README.md` 的评测协议。
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
QUESTIONS = HERE / "questions.jsonl"
ANSWERS = HERE / "answers.jsonl"

ENDPOINT_PATH = "/ai/agent/chat"


# ---------------------------------------------------------------------------
# SSE 解析（纯函数，便于自测）
# ---------------------------------------------------------------------------
def parse_sse_block(block):
    """解析一个 SSE 事件块（已按空行切分），返回 {'event':..., 'data':...} 或 None。

    对格式保持宽容：Spring 的 SseEmitter 写 `event:<name>` / `data:<json>`（冒号后**无空格**），
    但其他实现常带空格，两种都接受；`data` 按 SSE 规范允许分多行，这里拼接。
    """
    event_name = None
    data_parts = []
    for line in block.splitlines():
        if not line or line.startswith(":"):
            continue
        if line.startswith("event:"):
            event_name = line[len("event:"):].strip()
        elif line.startswith("data:"):
            data_parts.append(line[len("data:"):].lstrip())
    if event_name is None and not data_parts:
        return None
    payload = None
    if data_parts:
        joined = "\n".join(data_parts)
        try:
            payload = json.loads(joined)
        except json.JSONDecodeError:
            payload = {"_raw": joined, "_parse_error": True}
    return {"event": event_name, "data": payload}


def parse_sse_stream(text):
    """把整段 SSE 文本切成事件列表，按空行分块。"""
    events = []
    for block in text.replace("\r\n", "\n").split("\n\n"):
        parsed = parse_sse_block(block)
        if parsed is not None:
            events.append(parsed)
    return events


def extract_result(events):
    """从事件列表里取出回答正文、引用、拒答原因与状态。

    与后端结构对齐（`AgentOrchestrator.finish`）：
    - 回答正文放在 final 事件的 `message`（不是 `data`）
    - 引用列表放在 final 事件的 `data.citations`
    - 拒答原因放在 `data.reason`；状态由"是否有 reason"推断
    """
    result = {"answer": None, "citations": [], "reason": None, "steps": None,
              "status": "ERROR", "step_citations": [], "tool_trace": [], "event_types": []}
    for event in events:
        name = event.get("event")
        data = event.get("data") or {}
        if name:
            result["event_types"].append(name)
        if name == "step":
            tool = data.get("toolName")
            if tool:
                result["tool_trace"].append(tool)
            payload = data.get("data") or {}
            for key in ("stepCitations", "citations"):
                found = payload.get(key)
                if isinstance(found, list) and found:
                    result["step_citations"].extend(found)
                    break
        elif name == "final":
            result["answer"] = data.get("message")
            payload = data.get("data") or {}
            citations = payload.get("citations")
            if isinstance(citations, list):
                result["citations"] = citations
            result["reason"] = payload.get("reason")
            result["steps"] = payload.get("steps")
            # 有 reason 即为拒答路径：编排层在 DONE 分支传的 reason 为 null。
            result["status"] = "REFUSED" if result["reason"] else "DONE"
        elif name == "error":
            result["status"] = "ERROR"
            result["answer"] = None
    if result["answer"] is None and result["status"] != "ERROR":
        result["status"] = "ERROR"
    return result


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
def request_stream(base_url, question, crop, session_id, timeout=120):
    body = json.dumps({"sessionId": session_id, "question": question, "crop": crop}).encode("utf-8")
    request = urllib.request.Request(
        base_url.rstrip("/") + ENDPOINT_PATH, data=body, method="POST",
        headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read().decode("utf-8", errors="replace")


def load_jsonl(path):
    if not path.is_file():
        return []
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def preflight(base_url):
    """启动检查：后端可达、key 已设置。失败时给出确切原因，不要让人对着空答案排查。"""
    problems = []
    if not os.environ.get("DEEPSEEK_API_KEY"):
        problems.append("未设置 DEEPSEEK_API_KEY——后端读的正是这个变量（application.properties:19）")
    try:
        request = urllib.request.Request(base_url.rstrip("/") + "/ai/knowledge/status", method="GET")
        with urllib.request.urlopen(request, timeout=10) as response:
            response.read()
    except urllib.error.HTTPError as error:
        # 接口存在但返回错误码，仍说明服务在跑
        if error.code >= 500:
            problems.append("后端 /ai/knowledge/status 返回 {}，知识库可能未就绪".format(error.code))
    except Exception as error:
        problems.append("后端不可达（{}）：{}".format(base_url, error))
    return problems


def run_selftest():
    """用与后端结构一致的合成 SSE 流验证解析逻辑——抓取本身需要平台，但解析不能是这个盲区。"""
    done_stream = (
        'event:step\n'
        'data:{"type":"step","stepNo":1,"toolName":"knowledgeSearch",'
        '"message":"检索知识库","data":{"stepCitations":[{"index":1,"name":"番茄早疫病"}]}}\n\n'
        'event:step\n'
        'data:{"type":"step","stepNo":2,"toolName":"FINALIZE","message":"整理证据","data":{}}\n\n'
        'event:final\n'
        'data:{"type":"final","stepNo":2,"toolName":null,"message":"结论：……依据[1]。",'
        '"data":{"reason":null,"citationCount":1,"steps":2,"citations":[{"index":1,"name":"番茄早疫病"}]}}\n\n'
    )
    refused_stream = (
        'event:step\n'
        'data:{"type":"step","stepNo":1,"toolName":"knowledgeSearch","message":"检索","data":{}}\n\n'
        'event:final\n'
        'data:{"type":"final","stepNo":1,"toolName":null,"message":"资料库中未找到足够依据……",'
        '"data":{"reason":"NO_RELIABLE_EVIDENCE","citationCount":0,"steps":1,"citations":[]}}\n\n'
    )
    error_stream = 'event:error\ndata:{"message":"智能体执行失败"}\n\n'

    checks = []
    done = extract_result(parse_sse_stream(done_stream))
    checks.append(("DONE 状态", done["status"] == "DONE"))
    checks.append(("回答正文取自 message", done["answer"] == "结论：……依据[1]。"))
    checks.append(("引用列表解析", len(done["citations"]) == 1))
    checks.append(("步进引用解析", len(done["step_citations"]) == 1))
    checks.append(("工具轨迹顺序", done["tool_trace"] == ["knowledgeSearch", "FINALIZE"]))

    refused = extract_result(parse_sse_stream(refused_stream))
    checks.append(("REFUSED 状态", refused["status"] == "REFUSED"))
    checks.append(("拒答原因", refused["reason"] == "NO_RELIABLE_EVIDENCE"))
    checks.append(("拒答仍有正文", bool(refused["answer"])))

    failed = extract_result(parse_sse_stream(error_stream))
    checks.append(("ERROR 状态", failed["status"] == "ERROR"))

    # 容错：CRLF 换行、冒号后带空格、data 分多行
    tolerant = parse_sse_stream('event: final\r\ndata: {"a":\r\n')
    checks.append(("CRLF 与不完整 JSON 不崩", len(tolerant) == 1))

    print("SSE 解析自测：")
    for name, ok in checks:
        print("  {} {}".format("通过" if ok else "失败", name))
    failures = [name for name, ok in checks if not ok]
    if failures:
        print("\n失败项：{}".format("；".join(failures)))
        return 1
    print("\n全部通过（{} 项）".format(len(checks)))
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--selftest", action="store_true", help="只验证 SSE 解析逻辑，不联网")
    parser.add_argument("--base", default="http://localhost:9999")
    parser.add_argument("--partition", default=None, help="只抓某个分区（dev / holdout）")
    parser.add_argument("--force", action="store_true", help="重抓已有 id")
    parser.add_argument("--sleep", type=float, default=1.0, help="每次请求间隔秒数")
    args = parser.parse_args()

    if args.selftest:
        return run_selftest()

    questions = load_jsonl(QUESTIONS)
    if not questions:
        sys.exit("题目文件为空：{}".format(QUESTIONS))
    if args.partition:
        questions = [q for q in questions if q.get("partition") == args.partition]
        if not questions:
            sys.exit("没有 partition={} 的题目".format(args.partition))

    problems = preflight(args.base)
    if problems:
        print("启动检查未通过：")
        for item in problems:
            print("  - " + item)
        sys.exit(1)

    existing = {row["id"]: row for row in load_jsonl(ANSWERS)}
    todo = [q for q in questions if args.force or q["id"] not in existing]
    print("题目 {} 条，待抓 {} 条（已有 {} 条）".format(len(questions), len(todo), len(existing)))

    with ANSWERS.open("a", encoding="utf-8", newline="\n") as handle:
        for index, item in enumerate(todo, start=1):
            session_id = "rate-{}-{}".format(item["id"], int(time.time()))
            try:
                stream = request_stream(args.base, item["question"], item.get("crop"), session_id)
                parsed = extract_result(parse_sse_stream(stream))
            except Exception as error:
                parsed = {"answer": None, "citations": [], "reason": None, "steps": None,
                          "status": "ERROR", "step_citations": [], "tool_trace": [],
                          "event_types": []}
                parsed["transport_error"] = str(error)[:300]
            record = {
                "id": item["id"],
                "partition": item.get("partition"),
                "crop": item.get("crop"),
                "question": item["question"],
                "expect": item.get("expect"),
                "sessionId": session_id,
                "status": parsed["status"],
                "answer": parsed["answer"],
                "citations": parsed["citations"],
                "refusalReason": parsed["reason"],
                "steps": parsed["steps"],
                "toolTrace": parsed["tool_trace"],
                "eventTypes": parsed["event_types"],
                "transportError": parsed.get("transport_error"),
                "capturedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            }
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
            handle.flush()
            print("  [{}/{}] {} → {}（引用 {} 条{}）".format(
                index, len(todo), item["id"], record["status"], len(record["citations"]),
                "，原因 " + record["refusalReason"] if record["refusalReason"] else ""))
            time.sleep(args.sleep)

    print("\n已写入 {}".format(ANSWERS))
    print("下一步：用 rater.html 打开评分，再运行 to_autometrics.py 转成评测数据集。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
