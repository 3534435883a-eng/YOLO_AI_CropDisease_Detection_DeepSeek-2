"""Exercise production ImagePredictor on every repository sample and the live HTTP flow.

Filename clues are consistency hints, never expert ground truth or independent mAP.
"""
from __future__ import annotations

import contextlib
import csv
import hashlib
import io
import json
import statistics
import sys
import time
from collections import Counter
from pathlib import Path
from unittest.mock import patch

import numpy as np
from PIL import Image
from ultralytics import YOLO
from ultralytics.utils import LOGGER

from audit_boxed_records import CROPS, OUT, ROOT, SESSION, BASE, normalize_label, predict, upload

sys.path.insert(0, str(ROOT / "YOLO_AI_CropDisease_Detection_Flask"))
from predict.predictImg import ImagePredictor


def filename_hint(kind, name):
    name = name.lower()
    rules = {
        "apple": [("c-rust", [2]), ("scab", [1]), ("rs_hl", [3]), ("jr_frge", [0])],
        "cotton": [("healthy", [2]), ("blight", [0]), ("curl", [1]), ("wilt", [3, 4]), ("fus", [3, 4])],
        "grape": [("black_rot", [0]), ("downey_mildew", [1]), ("esca", [2]), ("healthy", [3]), ("leaf_blight", [4])],
        "potato": [("early", [0]), ("late", [2]), ("healthy", [1])],
        "strawberry": [("angular_leafspot", [0]), ("anthracnose", [1]), ("blossom_blight", [2]),
                       ("gray_mold", [3]), ("leaf_spot", [4]), ("powdery_mildew_fruit", [5]), ("powdery_mildew_leaf", [6])],
        "tomato": [("early", [0]), ("leaf_miner", [3]), ("late", [2]), ("healthy", [1])],
        "wheat": [("bacterial_leaf_streak", [0]), ("head_scab", [1]), ("leaf_rust", [2]), ("loose_smut", [3]),
                  ("powdery_mildew", [4]), ("septoria_blotch", [5]), ("stem_rust", [6]), ("stripe_rust", [7])],
        "corn": [("largegls", [1])],
    }
    for token, indices in rules.get(kind, []):
        if token in name:
            return {"token": token, "class_indices": indices, "basis": "filename clue, unverified"}
    return None


class ModelTap:
    def __init__(self, model):
        self.model = model
        self.names = model.names
        self.raw = []
        self.rendered = []

    def __call__(self, *args, **kwargs):
        kwargs["verbose"] = False
        self.rendered = self.model(*args, **kwargs)
        self.raw = []
        for result in self.rendered:
            for box in result.boxes:
                idx = int(box.cls.item())
                self.raw.append({"class_index": idx, "label": normalize_label(self.names[idx]),
                                 "score": float(box.conf.item()), "xyxy": box.xyxy[0].tolist()})
        return self.rendered


def run_case(tap, weight, kind, source, output, threshold):
    output.parent.mkdir(parents=True, exist_ok=True)
    start = time.perf_counter()
    try:
        with patch("predict.predictImg.YOLO", return_value=tap), contextlib.redirect_stdout(io.StringIO()):
            predictor = ImagePredictor(str(weight), str(source), kind, str(output), threshold)
            data = predictor.predict()
        detections = list(tap.raw)
        ok = isinstance(data.get("labels"), list) and bool(data["labels"])
        overwritten = []
        if ok and tap.rendered:
            for d in detections:
                printed_name = tap.rendered[0].names.get(d["class_index"], "")
                actual_text = f"{d['score'] * 100:.2f}%"
                if not printed_name.endswith(actual_text):
                    overwritten.append({"class_index": d["class_index"], "actual_score": actual_text, "rendered_name": printed_name})
        return {"status": "detected" if ok else "no_detection", "labels": data.get("labels") if ok else [],
                "confidence_text": data.get("confidences") if ok else [], "model_reported_time": data.get("allTime"),
                "seconds": round(time.perf_counter() - start, 4), "detections": detections,
                "threshold_violation": any(d["score"] < threshold - 1e-6 for d in detections),
                "box_caption_score_errors": overwritten,
                "result_file": str(output.relative_to(OUT)).replace("\\", "/") if output.exists() and ok else None}
    except Exception as exc:
        return {"status": "error", "seconds": round(time.perf_counter() - start, 4), "error": str(exc), "detections": [], "labels": []}


def main():
    LOGGER.setLevel("WARNING")
    OUT.mkdir(parents=True, exist_ok=True)
    weights_dir = ROOT / "YOLO_AI_CropDisease_Detection_Flask/weights"
    all_results = []
    metadata = []
    stress = []
    http = []
    local_log = OUT / "local_results.jsonl"
    existing = {}
    if local_log.exists():
        for line in local_log.read_text(encoding="utf-8").splitlines():
            item = json.loads(line)
            existing[(item["kind"], item["image_name"], item["threshold"])] = item
    total_images = sum(len(list((ROOT / "测试图片" / crop).glob("*.jpg"))) for crop in CROPS.values())
    done_images = 0
    rng = np.random.default_rng(20261005)
    fixtures = OUT / "fixtures"
    fixtures.mkdir(exist_ok=True)
    for name, arr in [("white", np.full((640, 640, 3), 255, dtype=np.uint8)),
                      ("black", np.zeros((640, 640, 3), dtype=np.uint8)),
                      ("noise", rng.integers(0, 256, (640, 640, 3), dtype=np.uint8))]:
        Image.fromarray(arr).save(fixtures / f"{name}.jpg")
    for crop_num, (kind, crop) in enumerate(CROPS.items()):
        weight = weights_dir / f"{kind}_best.pt"
        start = time.perf_counter()
        model = YOLO(str(weight))
        tap = ModelTap(model)
        names = model.names
        metadata.append({"kind": kind, "crop": crop, "weight": weight.name, "sha256": hashlib.sha256(weight.read_bytes()).hexdigest(),
                         "names": names, "load_seconds": round(time.perf_counter() - start, 3),
                         "checkpoint_training_metrics": model.ckpt.get("train_metrics"),
                         "checkpoint_training_data": model.ckpt.get("train_args", {}).get("data"),
                         "duplicate_labels": {label: indices for label in set(names.values())
                                              if len(indices := [i for i, x in names.items() if x == label]) > 1},
                         "invisible_label_characters": {i: repr(label) for i, label in names.items() if "\u200b" in label}})
        images = sorted((ROOT / "测试图片" / crop).glob("*.jpg"))
        print(f"开始 {crop}: {len(images)} images / {len(names)} classes", flush=True)
        for index, source in enumerate(images):
            hint = filename_hint(kind, source.name)
            for threshold in [0.1, 0.5, 0.8]:
                key = (kind, source.name, threshold)
                if key in existing:
                    item = existing[key]
                else:
                    rel_output = Path("predictions") / kind / f"{index:02d}" / f"threshold-{threshold:.1f}.jpg"
                    item = {"kind": kind, "crop": crop, "image_name": source.name, "image_index": index,
                            "source_file": str(source), "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                            "threshold": threshold, "filename_hint": hint, "ground_truth": False,
                            **run_case(tap, weight, kind, source, OUT / rel_output, threshold)}
                    detected_classes = [d["class_index"] for d in item["detections"]]
                    item["filename_hint_consistent"] = bool(set(detected_classes) & set(hint["class_indices"])) if hint else None
                    with local_log.open("a", encoding="utf-8") as handle:
                        handle.write(json.dumps(item, ensure_ascii=False) + "\n")
                all_results.append(item)
            done_images += 1
            if (index + 1) % 5 == 0 or index + 1 == len(images):
                print(f"进度 {done_images}/{total_images} images; {crop} {index + 1}/{len(images)}", flush=True)
        for name in ["white", "black", "noise"]:
            src = fixtures / f"{name}.jpg"
            stress.append({"kind": kind, "crop": crop, "fixture": name,
                           **run_case(tap, weight, kind, src, OUT / "stress" / f"{kind}-{name}.jpg", 0.5)})
        other_kind = list(CROPS)[(crop_num + 1) % len(CROPS)]
        other_image = sorted((ROOT / "测试图片" / CROPS[other_kind]).glob("*.jpg"))[0]
        stress.append({"kind": kind, "crop": crop, "fixture": "wrong_crop", "actual_crop": CROPS[other_kind],
                       "source_file": str(other_image),
                       **run_case(tap, weight, kind, other_image, OUT / "stress" / f"{kind}-wrong-crop.jpg", 0.5)})
        # One real upload/prediction request per crop, plus the currently weakest local sample.
        baseline = [x for x in all_results if x["kind"] == kind and x["threshold"] == 0.5]
        weakest = min(baseline, key=lambda x: max([d["score"] for d in x["detections"]], default=0))
        selected = list(dict.fromkeys([str(images[0]), weakest["source_file"]]))
        for sample_no, src in enumerate(selected):
            item = {"kind": kind, "crop": crop, "source_file": src, "threshold": 0.5, "ground_truth": False}
            try:
                url = upload(src, f"audit-{kind}-{sample_no}")
                item.update(predict(url, kind, weight.name, 0.5))
                target = next(x for x in baseline if x["source_file"] == src)
                item["matches_local_label_sequence"] = [normalize_label(x) for x in item.get("labels", [])] == [normalize_label(x) for x in target.get("labels", [])]
                if item.get("out_image_url"):
                    r = SESSION.get(item["out_image_url"], timeout=20)
                    r.raise_for_status()
                    dest = OUT / "http" / f"{kind}-{sample_no}.jpg"
                    dest.parent.mkdir(exist_ok=True)
                    dest.write_bytes(r.content)
                    item["result_file"] = str(dest.relative_to(OUT)).replace("\\", "/")
            except Exception as exc:
                item.update({"code": "EXCEPTION", "message": str(exc)})
            http.append(item)
            print(f"HTTP {crop} {sample_no + 1}/{len(selected)}: {item.get('code')} {item.get('labels', item.get('message'))}", flush=True)
        (OUT / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
        (OUT / "stress_results.json").write_text(json.dumps(stress, ensure_ascii=False, indent=2), encoding="utf-8")
        (OUT / "http_results_all_plants.json").write_text(json.dumps(http, ensure_ascii=False, indent=2), encoding="utf-8")
    summary = {"samples": total_images, "cases": len(all_results), "status_counts": dict(Counter(x["status"] for x in all_results)),
               "threshold_violations": sum(bool(x.get("threshold_violation")) for x in all_results),
               "box_caption_error_images_at_05": sum(bool(x.get("box_caption_score_errors")) for x in all_results if x["threshold"] == 0.5),
               "crops": [], "threshold_monotonicity_errors": []}
    for kind, crop in CROPS.items():
        rows = [x for x in all_results if x["kind"] == kind and x["threshold"] == 0.5]
        hint_rows = [x for x in rows if x.get("filename_hint")]
        classes = sorted(set(d["class_index"] for x in rows for d in x["detections"]))
        summary["crops"].append({"kind": kind, "crop": crop, "images": len(rows),
                                  "detected": sum(x["status"] == "detected" for x in rows),
                                  "no_detection": sum(x["status"] == "no_detection" for x in rows),
                                  "errors": sum(x["status"] == "error" for x in rows),
                                  "hint_images": len(hint_rows), "hint_consistent": sum(bool(x["filename_hint_consistent"]) for x in hint_rows),
                                  "observed_class_indices": classes, "mean_cached_seconds": round(statistics.mean(x["seconds"] for x in rows), 3)})
        for name in set(x["image_name"] for x in rows):
            counts = [len(x["detections"]) for x in sorted([x for x in all_results if x["kind"] == kind and x["image_name"] == name], key=lambda x: x["threshold"])]
            if counts != sorted(counts, reverse=True):
                summary["threshold_monotonicity_errors"].append({"kind": kind, "image_name": name, "counts": counts})
    (OUT / "all_plants_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    with (OUT / "all_plants_results.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        fields = ["crop", "image_name", "threshold", "status", "labels", "confidence_text", "seconds", "filename_hint_consistent", "result_file", "source_file"]
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for item in all_results:
            writer.writerow({**item, "labels": " | ".join(item.get("labels", [])), "confidence_text": " | ".join(item.get("confidence_text", []))})
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
