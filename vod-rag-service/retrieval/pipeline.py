"""检索链路: 双路召回 -> RRF 融合 -> (docstore 取父/祖节点) -> AutoMerge -> Rerank -> 查询引擎"""
from typing import List

from llama_index.core import QueryBundle, StorageContext, VectorStoreIndex
from llama_index.core.bridge.pydantic import PrivateAttr
from llama_index.core.llms.mock import MockLLM
from llama_index.core.query_engine import RetrieverQueryEngine
from llama_index.core.retrievers import (
    AutoMergingRetriever,
    BaseRetriever,
    QueryFusionRetriever,
)
from llama_index.core.schema import NodeRelationship, NodeWithScore
from llama_index.storage.docstore.mongodb import MongoDocumentStore

from config import AUTOMERGE_SIMPLE_RATIO_THRESH, FUSION_TOP_K, QUERY_LLM, RRF_K
from llm import build_reranker


class PreloadedRetriever(BaseRetriever):
    """把 RRF 已检索到的子节点直接作为检索结果, 供 AutoMergingRetriever 消费(避免重复检索)"""
    _nodes: List[NodeWithScore] = PrivateAttr()

    def __init__(self, nodes: List[NodeWithScore]):
        super().__init__()
        self._nodes = nodes

    def _retrieve(self, query_bundle: QueryBundle) -> List[NodeWithScore]:
        return self._nodes


def build_fusion_retriever(vec_index: VectorStoreIndex, es_retriever) -> QueryFusionRetriever:
    """双路召回(Milvus 密集向量 + ES BM25) -> RRF 融合"""
    vec_retriever = vec_index.as_retriever(similarity_top_k=FUSION_TOP_K)
    return QueryFusionRetriever(
        retrievers=[vec_retriever, es_retriever],
        mode="reciprocal_rerank",
        # rrf_k: llama-index-core 0.14 已移除该参数(RRF 常数内置 k=60)
        num_queries=1,        # 不做查询改写, 无需 LLM
        llm=MockLLM(),  # num_queries=1 不会真正调用; 避免 Settings.llm 解析 OpenAI
        similarity_top_k=FUSION_TOP_K,
        use_async=False,
    )


def fetch_ancestors(leaf_hits: List[NodeWithScore],
                    docstore: MongoDocumentStore) -> List[dict]:
    """
    通过 RRF 命中的子节点 id, 从 docstore(MongoDB) 获取父节点与祖节点:
    leaf --PARENT--> parent 节点 --PARENT--> 祖节点(root 层)
    """
    info = []
    for hit in leaf_hits:
        node_id = hit.node.id_
        pid = hit.node.relationships.get(NodeRelationship.PARENT)
        parent_id = pid.node_id if pid else None
        ancestor_id = None
        if parent_id:
            parent = docstore.get_node(parent_id, raise_error=False)     # 父节点
            if parent is not None:
                gpid = parent.relationships.get(NodeRelationship.PARENT)
                ancestor_id = gpid.node_id if gpid else None             # 祖节点
        info.append({
            "leaf_id": node_id,
            "leaf_score": hit.score,
            "parent_id": parent_id,
            "ancestor_id": ancestor_id,
        })
    return info


def retrieve_with_automerge(query: str, fusion_retriever: QueryFusionRetriever,
                            docstore: MongoDocumentStore, reranker=None,
                            simple_ratio_thresh: float = AUTOMERGE_SIMPLE_RATIO_THRESH):
    """
    检索编排:
      1) RRF 融合 -> 子节点(id + score)
      2) 子节点 id 经 docstore.get_node 获取父节点与祖节点
      3) PreloadedRetriever 携带 RRF 结果 -> AutoMergingRetriever 从 docstore
         读父/祖节点完成父子合并
      4) Rerank 精排
    返回 (精排后节点列表, 父/祖节点信息)
    """
    # 1) RRF 融合, 得到子节点
    leaf_hits = fusion_retriever.retrieve(QueryBundle(query_str=query))
    # 2) 获取父节点与祖节点
    ancestors_info = fetch_ancestors(leaf_hits, docstore)
    # 3) AutoMerge: 从 docstore(MongoDB) 读父/祖节点完成合并
    storage_context = StorageContext.from_defaults(docstore=docstore)
    auto_merger = AutoMergingRetriever(
        PreloadedRetriever(leaf_hits),
        storage_context,
        simple_ratio_thresh=simple_ratio_thresh,
        verbose=False,
    )
    merged = auto_merger.retrieve(QueryBundle(query_str=query))
    # 4) 精排
    if reranker is not None:
        merged = reranker.postprocess_nodes(merged, QueryBundle(query_str=query))
    return merged, ancestors_info


def build_query_engine(vec_index: VectorStoreIndex, es_retriever,
                       storage_context: StorageContext):
    """
    完整查询引擎(RRF -> AutoMerge -> Rerank -> 响应合成), 供 /query 接口与 main.py 使用。
    storage_context 需为已写入全部层级节点(all_nodes)的 docstore, 供 AutoMerge 查父节点。
    """
    # 1) RRF 融合
    fusion_retriever = build_fusion_retriever(vec_index, es_retriever)
    # 2) 父子节点合并: RRF 命中的 leaf 集中在同一父节点下(占比超阈值)则合并返回父节点
    #    注意: 源码参数名为 vector_retriever / simple_ratio_thresh, 阈值取值 (0, 1]
    auto_merge_retriever = AutoMergingRetriever(
        fusion_retriever,
        storage_context,
        simple_ratio_thresh=AUTOMERGE_SIMPLE_RATIO_THRESH,
        verbose=True,
    )
    # 3) 精排: 对 AutoMerge 合并后的上下文重排 (本地 bge-reranker-base)
    reranker = build_reranker()
    # 4) 查询引擎: AutoMerge 结果先经 reranker 精排, 再交给响应合成
    #    QUERY_LLM 为 None 时用 MockLLM, 仅验证检索链路; 配置真实 LLM 即可完整问答
    query_engine = RetrieverQueryEngine.from_args(
        retriever=auto_merge_retriever,
        llm=QUERY_LLM or MockLLM(),
        node_postprocessors=[reranker],
    )
    return query_engine, auto_merge_retriever, reranker


