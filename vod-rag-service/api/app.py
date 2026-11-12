"""
多模态RAG API 统一入口 (摄入 + 检索 合并到一个进程 / 一个容器)

路由:
  POST /ingest/ingest    摄入 PDF
  GET  /ingest/health    摄入服务健康检查
  POST /retrieve/retrieve  RRF 融合检索
  POST /retrieve/query     完整问答 (检索 + LLM 合成)
  GET  /retrieve/health    检索服务健康检查
  GET  /retrieve/top_k    查看检索参数

启动: uvicorn api.app:app --host 0.0.0.0 --port 8000 --reload
文档: http://127.0.0.1:8000/docs
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import ingest_api, retrieve_api


@asynccontextmanager
async def lifespan(app: FastAPI):
    """统一生命周期: 先启动摄入模块, 再启动检索模块"""
    # 启动时初始化各自的 _state (embedder / retrievers / docstore / reranker)
    async with ingest_api.lifespan(app):
        async with retrieve_api.lifespan(app):
            yield
    # 退出时 _state.clear() 已由各子 lifespan 的 finally 块处理


app = FastAPI(title="多模态RAG API", version="2.0", lifespan=lifespan)


@app.get("/")
def root():
    return {
        "service": "multimodal-rag-api",
        "version": "2.0",
        "endpoints": {
            "ingest": ["/ingest/health", "/ingest/ingest"],
            "retrieve": ["/retrieve/health", "/retrieve/retrieve",
                         "/retrieve/query", "/retrieve/top_k"],
        },
        "docs": "/docs",
    }


app.include_router(ingest_api.router, prefix="/ingest", tags=["ingest"])
app.include_router(retrieve_api.router, prefix="/retrieve", tags=["retrieve"])
