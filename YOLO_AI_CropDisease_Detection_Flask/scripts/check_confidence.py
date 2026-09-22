# -*- coding: utf-8 -*-
"""置信度处理自检：入参 conf 必须生效；置信度不得被展示性放大。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from predict.predictImg import ImagePredictor  # noqa: E402


class _Stub:
    """占位模型，避免自检时加载权重。"""

    def __call__(self, *args, **kwargs):
        raise AssertionError("stub model must not be invoked")


def main():
    import predict.predictImg as module

    original = module.YOLO
    module.YOLO = lambda weights: _Stub()
    try:
        predictor = ImagePredictor("../weights/rice_best.pt", "../rice_test.png", "rice", conf=0.42)
        assert abs(predictor.conf - 0.42) < 1e-9, "conf must respect constructor argument, got %s" % predictor.conf
        mapped = predictor.map_confidence(0.31)
        assert abs(mapped - 0.31) < 1e-9, "confidence must not be inflated, got %s" % mapped
    finally:
        module.YOLO = original
    print("CONFIDENCE CHECK OK")


if __name__ == "__main__":
    main()
