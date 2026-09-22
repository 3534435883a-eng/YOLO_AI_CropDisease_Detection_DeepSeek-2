# -*- coding: utf-8 -*-
"""向量服务自检：维度、归一化、同病同症判别力。不需要 pytest。

注意：bge 对"番茄早疫病 / 番茄晚疫病"这类仅一字之差的近义病名区分度天然较低
（实测两者余弦高于"病名-症状描述"），因此本自检只断言**同病同症 > 异作物/无关文本**，
近义病名的区分交由 BM25 关键词与知识图谱承担。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from predict.embedding import embed  # noqa: E402


def cosine(a, b):
    return sum(x * y for x, y in zip(a, b))


def main():
    vectors = embed(["番茄早疫病", "番茄叶片出现褐色轮纹斑"])
    assert len(vectors) == 2, "expected 2 vectors, got %d" % len(vectors)
    assert len(vectors[0]) == 512, "expected dim 512, got %d" % len(vectors[0])
    assert abs(cosine(vectors[0], vectors[0]) - 1.0) < 1e-3, "self cosine should be ~1"

    probes = embed([
        "番茄叶片出现褐色轮纹斑",
        "番茄叶片出现褐色轮纹斑，湿度大时病斑扩展迅速",
        "水稻叶片出现褐色斑点",
        "苹果果实表面出现黑色凹陷病斑",
    ])
    same_symptom = cosine(probes[0], probes[1])
    other_crop = cosine(probes[0], probes[2])
    unrelated = cosine(probes[0], probes[3])
    assert same_symptom > unrelated, "same-disease symptom should beat unrelated text"
    assert same_symptom > other_crop, "same-disease symptom should beat other-crop text"

    near_names = cosine(*embed(["番茄早疫病", "番茄晚疫病"]))
    print("EMBEDDING CHECK OK  dim=%d" % len(vectors[0]))
    print("  same_symptom=%.4f  other_crop=%.4f  unrelated=%.4f" % (same_symptom, other_crop, unrelated))
    print("  near_disease_names=%.4f (近义病名区分度低，需 BM25/KG 兜底)" % near_names)


if __name__ == "__main__":
    main()
