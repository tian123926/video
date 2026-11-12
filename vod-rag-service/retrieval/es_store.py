"""Elasticsearch 关键词(BM25)检索: LlamaIndex 官方 ES 连接器, 只入库 all_leaf(不含父节点)

build_es_keyword_index: 摄入端写入 leaf 节点
load_es_keyword_retriever: 检索端只连已有 index, 不写入
"""
from llama_index.core import StorageContext, VectorStoreIndex
from llama_index.core.embeddings import BaseEmbedding
from llama_index.core.schema import BaseNode
try:  # llama-index-vector-stores-elasticsearch 0.7.0 只导出 Async* 策略类, 同步 query() 路径实测可用
    from llama_index.vector_stores.elasticsearch import BM25Strategy
except ImportError:
    from llama_index.vector_stores.elasticsearch import AsyncBM25Strategy as BM25Strategy
from llama_index.vector_stores.elasticsearch import ElasticsearchStore

from config import ES_INDEX, ES_RETRIEVAL_TOP_K, ES_URL


def build_es_store() -> ElasticsearchStore:
    """
    连接 ES (retrieval_strategy=BM25Strategy(): ES 原生 BM25 关键词检索,
    不需要稀疏向量模型; 若版本无 BM25Strategy, 换成 AsyncBM25Strategy 即可)。
    """
    return ElasticsearchStore(
        index_name=ES_INDEX,
        es_url=ES_URL,
        retrieval_strategy=BM25Strategy(),
    )


def build_es_keyword_index(nodes: list[BaseNode], embedder: BaseEmbedding) -> VectorStoreIndex:
    """摄入端: 只写入 all_leaf = leaf_nodes + struct_nodes, 父节点不进 ES"""
    es_store = build_es_store()
    # BM25 查询不使用向量, embed_model 仅为 VectorStoreIndex 构建接口所需
    return VectorStoreIndex(
        nodes,
        storage_context=StorageContext.from_defaults(vector_store=es_store),
        embed_model=embedder,
    )


def load_es_keyword_retriever(embedder: BaseEmbedding,
                              top_k: int = ES_RETRIEVAL_TOP_K):
    """检索端: 连接已有 ES index 构造 BM25 检索器, 不写入"""
    index = VectorStoreIndex.from_vector_store(build_es_store(), embed_model=embedder)
    return index.as_retriever(similarity_top_k=top_k)

