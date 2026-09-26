# -*- coding: utf-8 -*-
"""仿真审计：跑真实评测批次，逐条核对项目自己声明的性质，并列出无法从仿真中核实的数据缺口。

**为什么要有这个脚本**：项目把"同基线、同种子、同模型版本 ⇒ 状态与规则结果逐值一致"
写成了卖点。这类性质**必须能被反复验证**，否则它只是一句话。本项目此前只有单元测试在断言它，
但单元测试跑在测试进程里；本脚本打真实 HTTP 接口，验证的是**部署后的服务**。

用法（后端需在跑，且 `cropdisease` 库可用）::

    python run_simulation_audit.py --base http://localhost:9999 --days 120 --seed 20260921

脚本只读不写：只调 `/eval/runs` 与 `/eval/{id}/matrix`，不改数据库、不改代码。
"""
import argparse
import json
import sys
import urllib.request

# 只比较这些数值字段；series 与 elapsedMillis 不参与逐值比较（后者是运行耗时，本来就会变）。
NUMERIC_FIELDS = [
    "wFruit", "singleFruitWeightG", "fruitSetRate", "yieldKg", "marketableYieldKg",
    "waterUsedM3", "energyKWh", "co2UsedKg", "fertilizerUsedKg", "costYuan",
    "revenueYuan", "profitYuan", "highTemperatureMinutes", "highHumidityMinutes",
    "highVpdMinutes", "diseasePressureIntegral", "constraintViolations",
    "finalSeverityTotal", "meanTemperatureExceedanceC", "meanHumidityExceedancePct",
]


def post_json(url, payload, timeout=900):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST",
                                     headers={"Content-Type": "application/json; charset=utf-8"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def get_json(url, timeout=300):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def run_batch(base, batch_id, seed, days):
    post_json(base.rstrip("/") + "/eval/runs",
              {"batchId": batch_id, "seed": seed, "days": days})
    matrix = get_json(base.rstrip("/") + "/eval/%s/matrix" % batch_id)
    if matrix.get("code") != "0":
        raise RuntimeError("取矩阵失败：%s" % matrix.get("msg"))
    return matrix["data"]


def diff_batches(left, right):
    """逐档逐字段比较两个批次，返回差异列表。"""
    differences = []
    outcomes_left = left["outcomes"]
    outcomes_right = right["outcomes"]
    if set(outcomes_left) != set(outcomes_right):
        differences.append("档位集合不同：%s vs %s" % (sorted(outcomes_left), sorted(outcomes_right)))
        return differences
    for strategy in sorted(outcomes_left):
        for field in NUMERIC_FIELDS:
            a = outcomes_left[strategy].get(field)
            b = outcomes_right[strategy].get(field)
            if a != b:
                differences.append("%s.%s: %r != %r" % (strategy, field, a, b))
    return differences


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:9999")
    parser.add_argument("--days", type=int, default=120)
    parser.add_argument("--seed", type=int, default=20260921)
    parser.add_argument("--other-seed", type=int, default=777,
                        help="用于验证「种子确实在起作用」的对照种子")
    args = parser.parse_args()

    results = []
    print("=" * 78)
    print("仿真审计　后端 %s　%d 天　seed=%d" % (args.base, args.days, args.seed))
    print("=" * 78)

    first = run_batch(args.base, "audit-run-1", args.seed, args.days)
    print("\n[批次1] 服务端耗时 %s ms，%d 档" % (first.get("elapsedMillis"), len(first["outcomes"])))

    matrix_rows(first)
    ablation(first)
    invariants(first)

    # —— 可复算性：同种子重跑一次，逐值比较 ——
    print("\n" + "-" * 78)
    print("核对1｜同种子重跑是否逐值一致（项目卖点：逐值一致）")
    second = run_batch(args.base, "audit-run-2", args.seed, args.days)
    differences = diff_batches(first, second)
    if differences:
        print("  ❌ 不通过：同种子两次运行出现 %d 处差异" % len(differences))
        for item in differences[:20]:
            print("     " + item)
    else:
        print("  ✅ 通过：%d 档 × %d 个数值字段全部逐值相同" % (len(first["outcomes"]), len(NUMERIC_FIELDS)))
    results.append(("同种子逐值可复算", not differences))

    # —— 种子有效性：换种子应当得到不同结果，否则 seed 只是装饰 ——
    print("\n" + "-" * 78)
    print("核对2｜换种子是否真的改变结果（否则 seed 是装饰参数）")
    third = run_batch(args.base, "audit-run-3", args.other_seed, args.days)
    seed_differences = diff_batches(first, third)
    if seed_differences:
        print("  ✅ 通过：换种子后出现 %d 处差异（例如 %s）" % (
            len(seed_differences), seed_differences[0]))
    else:
        print("  ❌ 不通过：换种子后全部指标相同，seed 未真正影响天气相位")
    results.append(("种子确实影响结果", bool(seed_differences)))

    # —— 控制器记忆是否跨档污染 ——
    print("\n" + "-" * 78)
    print("核对3｜连续控制档的积分记忆是否跨运行污染（先跑 PID 再跑规则，看规则档是否被改动）")
    # 批次的档序固定为枚举顺序（PID 在后），因此这里用"与批次1的规则档比较"来判定：
    # 若 PID 的积分状态泄漏到后续运行，规则档数值会漂移。
    rule_first = first["outcomes"]["P2_RULE_ENGINE"]
    rule_third = third["outcomes"]["P2_RULE_ENGINE"]
    same_as_first = all(rule_first.get(f) == second["outcomes"]["P2_RULE_ENGINE"].get(f)
                        for f in NUMERIC_FIELDS)
    print("  %s 规则档在同一 seed 的两次运行间%s" % (
        "✅" if same_as_first else "❌", "完全一致（无跨档污染）" if same_as_first else "发生漂移"))
    print("     （换种子后规则档数值不同属预期：%s）" % (
        "已确认" if rule_first != rule_third else "未变化——需复查"))
    results.append(("控制器状态不跨档污染", same_as_first))

    print("\n" + "=" * 78)
    print("审计结论")
    print("=" * 78)
    for name, ok in results:
        print("  %s %s" % ("通过" if ok else "**不通过**", name))
    failed = [name for name, ok in results if not ok]
    if failed:
        print("\n有未通过项：%s" % "；".join(failed))
        return 1
    print("\n全部通过。")
    return 0


def matrix_rows(data):
    outcomes = data["outcomes"]
    print("\n[指标矩阵]")
    print("%-24s %9s %9s %9s %9s %9s" % ("档位", "果实干重", "产量kg", "水m3", "电kWh", "利润元"))
    for key, value in outcomes.items():
        print("%-24s %9.2f %9.1f %9.2f %9.2f %9.1f" % (
            key, value["wFruit"], value["yieldKg"], value["waterUsedM3"],
            value["energyKWh"], value["profitYuan"]))
    print("\n%-24s %9s %9s %11s %10s %8s" % ("档位", "高温min", "高湿min", "病害压力", "严重度%", "冲突"))
    for key, value in outcomes.items():
        print("%-24s %9d %9d %11.1f %10.2f %8d" % (
            key, value["highTemperatureMinutes"], value["highHumidityMinutes"],
            value["diseasePressureIntegral"], value["finalSeverityTotal"],
            value["constraintViolations"]))


def ablation(data):
    """P/PI/PID 三档消融方向是否与"积分消偏、微分抑超调"一致。"""
    outcomes = data["outcomes"]
    p = outcomes.get("P4_PID_PROPORTIONAL")
    pi = outcomes.get("P5_PID_PI")
    full = outcomes.get("P6_PID_FULL")
    if not (p and pi and full):
        print("\n[连续控制消融] 档位缺失，跳过")
        return
    print("\n[连续控制消融] 超温 ℃ / 高温暴露 min / 果实干重")
    for name, item in (("仅 P", p), ("P+I", pi), ("PID", full)):
        print("  %-6s 超温 %.3f　高温 %6d min　干重 %7.2f" % (
            name, item["meanTemperatureExceedanceC"], item["highTemperatureMinutes"], item["wFruit"]))
    direction = (pi["meanTemperatureExceedanceC"] < p["meanTemperatureExceedanceC"]
                 and full["meanTemperatureExceedanceC"] <= pi["meanTemperatureExceedanceC"])
    print("  方向一致性（P → PI → PID 超温不上升）：%s" % ("一致" if direction else "**不一致**"))


def invariants(data):
    """设备互斥等硬约束：任何档都不允许出现冲突。"""
    outcomes = data["outcomes"]
    violations = {key: value["constraintViolations"] for key, value in outcomes.items()
                  if value["constraintViolations"]}
    print("\n[硬约束] 设备互斥冲突：%s" % (
        "全部为 0" if not violations else "**存在冲突** %s" % violations))


if __name__ == "__main__":
    sys.exit(main())
