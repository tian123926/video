"""Rerank 精排配置: 本地 bge-reranker-base (魔搭下载), 供 node_postprocessors 使用"""
from llama_index.core.postprocessor import SentenceTransformerRerank

from config import RERANK_MODEL, RERANK_TOP_N


def build_reranker(top_n: int = RERANK_TOP_N,
                   device: str | None = None) -> SentenceTransformerRerank:
    """
    构造精排器, 使用魔搭下载的本地 bge-reranker-base (中英双语)。
    典型用法: RetrieverQueryEngine.from_args(..., node_postprocessors=[build_reranker()])
    """
    kwargs = {"device": device} if device else {}
    return SentenceTransformerRerank(model=RERANK_MODEL, top_n=top_n, **kwargs)
