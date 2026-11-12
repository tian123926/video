"""
摄入服务 API: PDF -> MinerU 解析 -> 多模态 Document -> 层级切片
  -> MongoDB docstore 存全部节点(含父/祖) -> Milvus/ES 只入 leaf

集成模式(推荐): uvicorn api.app:app --host 0.0.0.0 --port 8000
  路由挂载在 /ingest 下: /ingest/health, /ingest/ingest
独立运行: uvicorn api.ingest_api:standalone_app --host 127.0.0.1 --port 8001
"""
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import APIRouter, FastAPI
from llama_index.core import StorageContext, VectorStoreIndex
from llama_index.core.node_parser import get_leaf_nodes
from llama_index.core.schema import ImageDocument
from pydantic import BaseModel

from config import MINERU_BACKEND, MINERU_OUTPUT_DIR
from documents import build_hierarchical_nodes, build_multimodal_documents
from llm import Qwen3Embedding
from parsing import parse_pdf_with_mineru
from retrieval import build_es_keyword_index
from storage import build_mongo_docstore
from vectordb import build_vector_store

# router 级别状态 (被 api.app include 时, 与 retrieve_api 的状态互不干扰)
_state: dict = {}


@asynccontextmanager
async def lifespan(app):
    """集成模式下不会自动被调用, 由 api.app 统一管理"""
    _state["embedder"] = Qwen3Embedding()
    yield
    _state.clear()


router = APIRouter()


class IngestRequest(BaseModel):
    pdf_path: str                      # PDF 路径
    backend: str = MINERU_BACKEND      # pipeline / vlm-engine / hybrid-engine
    output_dir: str = MINERU_OUTPUT_DIR
    rebuild: bool = False              # True 重建 Milvus/ES(清空旧数据); False 增量摄入


class IngestResponse(BaseModel):
    pdf: str
    documents: int
    images: int
    hier_nodes: int                    # 文本层级节点(root/parent/leaf)
    struct_nodes: int                  # 结构化整体节点(表格/图片/公式)
    all_nodes_to_mongo: int            # 存入 MongoDB 的全部节点(含父/祖)
    leaf_indexed: int                  # 入 Milvus/ES 的 leaf 节点


@router.get("/health")
def health():
    return {"status": "ok", "service": "ingest"}


@router.post("/ingest", response_model=IngestResponse)
def ingest(req: IngestRequest):
    # 1. MinerU 本地解析 PDF -> markdown + content_list.json + images/
    md_path = parse_pdf_with_mineru(req.pdf_path, output_dir=req.output_dir,
                                    backend=req.backend)

    # 2. content_list.json -> 多模态 Document (图片/表格/公式/文字)
    content_list = Path(md_path).with_name(f"{Path(req.pdf_path).stem}_content_list.json")
    if not content_list.exists():
        raise FileNotFoundError(f"未找到 {content_list}, 请确认 MinerU 输出")
    docs = build_multimodal_documents(str(content_list), base_dir=content_list.parent)
    n_images = sum(1 for d in docs if isinstance(d, ImageDocument))

    # 3. HierarchicalNodeParser 层级切片 (表格/图片/公式不切片)
    all_nodes, hier_nodes, struct_nodes = build_hierarchical_nodes(docs)
    all_leaf = get_leaf_nodes(hier_nodes) + struct_nodes  # 向量库/ES 只入 leaf

    # 4. 全部节点(含父节点/祖节点) -> MongoDB docstore (检索期 AutoMerge 取父/祖)
    docstore = build_mongo_docstore()
    docstore.add_documents(all_nodes)

    # 5. leaf -> Milvus (向量索引) / ES (BM25)
    embedder = _state["embedder"]
    vector_store = build_vector_store(dim=embedder.embed_dim, overwrite=req.rebuild)
    storage_context = StorageContext.from_defaults(vector_store=vector_store)
    VectorStoreIndex(all_leaf, storage_context=storage_context,
                     embed_model=embedder, show_progress=True)
    build_es_keyword_index(all_leaf, embedder)

    return IngestResponse(
        pdf=req.pdf_path,
        documents=len(docs),
        images=n_images,
        hier_nodes=len(hier_nodes),
        struct_nodes=len(struct_nodes),
        all_nodes_to_mongo=len(all_nodes),
        leaf_indexed=len(all_leaf),
    )


# ---------- 独立运行支持 ----------
def _make_standalone():
    app = FastAPI(title="多模态RAG-摄入服务", version="1.0", lifespan=lifespan)
    app.include_router(router)
    return app


standalone_app = _make_standalone()

# 兼容旧文档中的 `api.ingest_api:app`
app = standalone_app
