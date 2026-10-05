"""Regression audit of locally retained boxed image records, not a ground-truth benchmark."""
from __future__ import annotations

import csv
import hashlib
import json
import re
import shutil
import time
from collections import Counter
from datetime import datetime
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/artifacts/2026-10-05-vision-audit"
BASE = "http://127.0.0.1:8100/api"
CROPS = {"corn": "玉米", "rice": "水稻", "wheat": "小麦", "potato": "马铃薯",
         "tomato": "番茄", "cotton": "棉花", "apple": "苹果", "grape": "葡萄", "strawberry": "草莓"}
SESSION = requests.Session()
SESSION.trust_env = False


def normalize_label(value):
    value = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m[1], 16)), str(value))
    return value.replace("\u200b", "").strip()


def decode_list(value):
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except (ValueError, TypeError):
            return []
    return value if isinstance(value, list) else []


def upload(path, tag):
    with Path(path).open("rb") as stream:
        response = SESSION.post(BASE + "/files/upload", files={"file": (tag + ".jpg", stream, "image/jpeg")}, timeout=30)
    response.raise_for_status()
    payload = response.json()
    if str(payload.get("code")) != "0":
        raise RuntimeError(str(payload))
    return payload["data"]


def predict(url, kind, weight, threshold):
    started = time.perf_counter()
    response = SESSION.post(BASE + "/flask/predict", json={
        "username": "vision-audit-20261005", "kind": kind, "weight": weight,
        "conf": threshold, "inputImg": url, "startTime": datetime.now().strftime("%Y-%m-%d %H:%M:%S")}, timeout=90)
    elapsed = time.perf_counter() - started
    response.raise_for_status()
    payload = response.json()
    item = {"code": str(payload.get("code")), "message": payload.get("msg"), "http_seconds": round(elapsed, 3)}
    if item["code"] == "0":
        data = json.loads(payload["data"]) if isinstance(payload.get("data"), str) else payload["data"]
        item.update({"labels": [normalize_label(x) for x in decode_list(data.get("label"))],
                     "confidences": decode_list(data.get("confidence")), "model_seconds": data.get("allTime"),
                     "out_image_url": data.get("outImg"), "response": data})
        item["score_below_threshold"] = any(float(str(x).rstrip("%")) / 100 < threshold - 0.0001 for x in item["confidences"])
    return item


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((OUT / "manifest.json").read_text(encoding="utf-8"))
    jsonl = OUT / "http_results.jsonl"
    finished = set()
    results = []
    if jsonl.exists():
        for line in jsonl.read_text(encoding="utf-8").splitlines():
            x = json.loads(line)
            results.append(x)
            finished.add((x["record_id"], x["variant"], x["threshold"]))
    total = sum(3 * (1 + bool(x.get("original_path"))) for x in manifest["selected"])
    for entry in manifest["selected"]:
        kind = entry["kind"]
        sample_dir = OUT / "samples" / str(entry["id"])
        sample_dir.mkdir(parents=True, exist_ok=True)
        for variant, path in [("boxed", entry["boxed_path"]), ("original", entry.get("original_path"))]:
            if not path:
                continue
            sample_copy = sample_dir / (variant + ".jpg")
            if not sample_copy.exists():
                shutil.copyfile(path, sample_copy)
            pending = [t for t in [0.1, 0.5, 0.8] if (entry["id"], variant, t) not in finished]
            if not pending:
                continue
            input_url = upload(path, f"audit-{entry['id']}-{variant}")
            for threshold in pending:
                item = {"record_id": entry["id"], "kind": kind, "crop": CROPS.get(kind, kind),
                        "variant": variant, "threshold": threshold, "weight": entry["weight"],
                        "input_file": path, "input_sha256": hashlib.sha256(Path(path).read_bytes()).hexdigest(),
                        "legacy_labels": [normalize_label(x) for x in decode_list(entry.get("legacy_label"))],
                        "legacy_confidences": decode_list(entry.get("legacy_confidence")),
                        "ground_truth": False}
                try:
                    item.update(predict(input_url, kind, entry["weight"], threshold))
                    if item.get("out_image_url"):
                        response = SESSION.get(item["out_image_url"], timeout=30)
                        response.raise_for_status()
                        result_file = sample_dir / f"{variant}-{threshold:.1f}-predicted.jpg"
                        result_file.write_bytes(response.content)
                        item["result_file"] = str(result_file.relative_to(OUT)).replace("\\", "/")
                except Exception as exc:
                    item.update({"code": "EXCEPTION", "message": str(exc)[:600]})
                with jsonl.open("a", encoding="utf-8") as handle:
                    handle.write(json.dumps(item, ensure_ascii=False) + "\n")
                results.append(item)
                finished.add((entry["id"], variant, threshold))
                print(f"[{len(results)}/{total}] {item['crop']} record={entry['id']} {variant} threshold={threshold}: "
                      f"{item.get('labels', item.get('message'))} ({item.get('http_seconds', '?')}s)", flush=True)
    summary = {
        "method": "Retained historical model outputs, plus corresponding raw originals, through Vite/Spring/Flask.",
        "not_ground_truth": True, "selected_images": len(manifest["selected"]),
        "crop_counts": dict(Counter(x["kind"] for x in manifest["selected"])),
        "cases": len(results), "recognized_cases": sum(x.get("code") == "0" for x in results),
        "no_detection_cases": sum(x.get("code") == "-1" and "该图片无法识别" in str(x.get("message")) for x in results),
        "errors": [x for x in results if x.get("code") != "0" and "该图片无法识别" not in str(x.get("message"))],
        "threshold_violations": [x for x in results if x.get("score_below_threshold")],
        "pairs": []}
    for entry in manifest["selected"]:
        pair = {"record_id": entry["id"], "kind": entry["kind"], "by_threshold": {}}
        for threshold in [0.1, 0.5, 0.8]:
            rows = [x for x in results if x["record_id"] == entry["id"] and x["threshold"] == threshold]
            by_variant = {x["variant"]: x for x in rows}
            boxed, original = by_variant.get("boxed", {}), by_variant.get("original", {})
            pair["by_threshold"][str(threshold)] = {
                "boxed_labels": boxed.get("labels", []), "original_labels": original.get("labels", []),
                "same_label_set": set(boxed.get("labels", [])) == set(original.get("labels", [])),
                "boxed_count": len(boxed.get("labels", [])), "original_count": len(original.get("labels", [])),
                "original_matches_old_label_set": set(original.get("labels", [])) == set(original.get("legacy_labels", []))}
        summary["pairs"].append(pair)
    (OUT / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    with (OUT / "results.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        fields = ["record_id", "crop", "variant", "threshold", "code", "labels", "confidences", "http_seconds", "message", "input_file", "result_file"]
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for item in results:
            writer.writerow({**item, "labels": " | ".join(item.get("labels", [])), "confidences": " | ".join(item.get("confidences", []))})
    print(json.dumps({k: v for k, v in summary.items() if k not in ["pairs", "errors"]}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
