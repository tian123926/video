"""
检索服务 API: RRF 融合 -> 子节点 id 取父/祖节点 -> AutoMerge -> Rerank

集成模式(推荐): uvicorn api.app:app --host 0.0.0.0 --port 8000
  路由挂载在 /retrieve 下: /retrieve/health, /retrieve/retrieve, /retrieve/query
独立运行: uvicorn api.retrieve_api:standalone_app --host 127.0.0.1 --port 8002
"""
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import APIRouter, FastAPI
from llama_index.core import StorageContext, VectorStoreIndex
from llama_index.core.schema import NodeWithScore
from pydantic import BaseModel

from config import AUTOMERGE_SIMPLE_RATIO_THRESH, RETRIEVAL_TOP_K
from llm import Qwen3Embedding, build_reranker
from retrieval import (
    build_fusion_retriever,
    build_query_engine,
    load_es_keyword_retriever,
    retrieve_with_automerge,
)
from storage import build_mongo_docstore
from vectordb import load_vector_store

_state: dict = {}


@asynccontextmanager
async def lifespan(app):
    """集成模式下不会自动被调用, 由 api.app 统一管理"""
    embedder = Qwen3Embedding()

    # Milvus 只读连接已有 collection
    vector_store = load_vector_store(dim=embedder.embed_dim)
    vec_index = VectorStoreIndex.from_vector_store(vector_store, embed_model=embedder)

    # ES 只读连接已有 index (BM25)
    es_retriever = load_es_keyword_retriever(embedder)

    # MongoDB docstore(全部层级节点, 供 AutoMerge 取父/祖)
    docstore = build_mongo_docstore()

    _state["fusion_retriever"] = build_fusion_retriever(vec_index, es_retriever)
    _state["docstore"] = docstore
    _state["reranker"] = build_reranker()
    # 完整问答引擎(RRF -> AutoMerge -> Rerank -> 响应合成)
    _state["query_engine"], _, _ = build_query_engine(
        vec_index, es_retriever, StorageContext.from_defaults(docstore=docstore))
    yield
    _state.clear()


router = APIRouter()


class RetrieveRequest(BaseModel):
    query: str
    simple_ratio_thresh: Optional[float] = None   # AutoMerge 合并阈值, 默认取 config


class RetrieveResponse(BaseModel):
    query: str
    leaf_hits: int                 # RRF 融合得到的子节点数
    merged_hits: int               # AutoMerge + Rerank 后节点数
    ancestors: list                # 每个子节点对应的父节点/祖节点 id
    nodes: list                    # 最终节点(含 score/type/page/text)


class QueryResponse(BaseModel):
    query: str
    response: str                  # LLM 合成答案(QUERY_LLM 未配置时为 Mock)
    source_nodes: list


def serialize_node(r: NodeWithScore) -> dict:
    m = r.node.metadata
    return {
        "node_id": r.node.id_,
        "score": float(r.score) if r.score is not None else None,
        "type": m.get("type"),
        "page": m.get("page"),
        "parent_id": m.get("parent_id"),
        "text": r.get_content()[:300],
    }


@router.get("/health")
def health():
    return {"status": "ok", "service": "retrieve"}


@router.post("/retrieve", response_model=RetrieveResponse)
def retrieve(req: RetrieveRequest):
    """检索链路: RRF -> 子节点 id 取父/祖节点 -> AutoMerge -> Rerank"""
    merged, ancestors_info = retrieve_with_automerge(
        req.query,
        _state["fusion_retriever"],
        _state["docstore"],
        reranker=_state["reranker"],
        simple_ratio_thresh=req.simple_ratio_thresh or AUTOMERGE_SIMPLE_RATIO_THRESH,
    )
    return RetrieveResponse(
        query=req.query,
        leaf_hits=len(ancestors_info),
        merged_hits=len(merged),
        ancestors=ancestors_info,
        nodes=[serialize_node(r) for r in merged],
    )


@router.post("/query", response_model=QueryResponse)
def query(req: RetrieveRequest):
    """完整问答: 检索链路 + LLM 响应合成"""
    resp = _state["query_engine"].query(req.query)
    return QueryResponse(
        query=req.query,
        response=str(resp),
        source_nodes=[serialize_node(n) for n in resp.source_nodes],
    )


@router.get("/top_k")
def get_top_k():
    """查看默认检索参数"""
    return {
        "retrieval_top_k": RETRIEVAL_TOP_K,
        "automerge_thresh": AUTOMERGE_SIMPLE_RATIO_THRESH,
    }


# ---------- 独立运行支持 ----------
def _make_standalone():
    app = FastAPI(title="多模态RAG-检索服务", version="1.0", lifespan=lifespan)
    app.include_router(router)
    return app


standalone_app = _make_standalone()

# 兼容旧文档中的 `api.retrieve_api:app`
app = standalone_app
