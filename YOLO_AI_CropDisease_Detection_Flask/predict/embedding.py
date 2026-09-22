# -*- coding: utf-8 -*-
"""向量化服务：懒加载 bge-small-zh-v1.5，供后端 /embed 调用。

模型名可用环境变量 EMBEDDING_MODEL 覆盖；加载失败不抛出到进程级，
由调用方（main.py 的 /embed 路由）转成结构化错误返回。
"""
import os
import threading

_MODEL_NAME = os.environ.get("EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5")
_lock = threading.Lock()
_model = None


def model_name():
    return _MODEL_NAME


def _load_model():
    global _model
    if _model is None:
        with _lock:
            if _model is None:
                from sentence_transformers import SentenceTransformer
                _model = SentenceTransformer(_MODEL_NAME)
    return _model


def embed(texts):
    """把文本列表编码为 L2 归一化向量列表（bge-small-zh 维度 512）。"""
    if not isinstance(texts, list) or not texts:
        raise ValueError("texts must be a non-empty list")
    model = _load_model()
    vectors = model.encode(texts, normalize_embeddings=True, convert_to_numpy=True)
    return vectors.tolist()
