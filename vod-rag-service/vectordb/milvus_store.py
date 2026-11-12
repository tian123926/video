"""Milvus 向量存储: 基于 LlamaIndex 官方 Milvus 连接器(llama-index-vector-stores-milvus)"""
from llama_index.vector_stores.milvus import MilvusVectorStore

from config import MILVUS_BASE_URL, MILVUS_COLLECTION, MILVUS_OVERWRITE


def build_vector_store(dim: int,
                       uri: str = MILVUS_BASE_URL,
                       collection: str = MILVUS_COLLECTION,
                       overwrite: bool = MILVUS_OVERWRITE) -> MilvusVectorStore:
    """
    构造官方 MilvusVectorStore 连接器。
    dim 必须与 embedding 维度一致(Qwen3-Embedding-0.6B 为 1024)。
    overwrite=True 时重建同名 collection, 首次运行后建议改为 False 增量插入。
    """
    store = MilvusVectorStore(
        uri=uri,
        collection_name=collection,
        dim=dim,
        overwrite=overwrite,
    )
    print(f"[Milvus] 连接 {uri} collection={collection} dim={dim} overwrite={overwrite}")
    return store


def load_vector_store(dim: int,
                      uri: str = MILVUS_BASE_URL,
                      collection: str = MILVUS_COLLECTION) -> MilvusVectorStore:
    """检索端: 只连接已有 collection, 不重建(overwrite=False)"""
    return build_vector_store(dim=dim, uri=uri, collection=collection, overwrite=False)
