"""全局配置: 集中管理路径、模型、切片、Milvus/MongoDB/ES 等参数

所有地址与路径均支持环境变量覆盖(docker 部署时由 compose 注入容器内值,
本地开发不设环境变量时使用默认值)。
"""
import os
from pathlib import Path


def _env(key: str, default: str = "") -> str:
    return os.getenv(key, default)


# ============ 输入 ============
PDF_PATH = _env("PDF_PATH", "demo.pdf")

# ============ MinerU 本地解析 ============
MINERU_OUTPUT_DIR = _env("MINERU_OUTPUT_DIR", "mineru_output")
MINERU_BACKEND = _env("MINERU_BACKEND", "pipeline")   # pipeline/vlm-engine/hybrid-engine
MINERU_API_URL = os.getenv("MINERU_API_URL") or None  # 常驻 mineru-api 时填, 否则 None

# ============ Qwen3-Embedding 本地向量化 ============
# 宿主机默认路径; 容器内注入 /models/Qwen3-Embedding-0.6B (volume 挂载)
EMBEDDING_MODEL_PATH = _env(
    "EMBEDDING_MODEL_PATH", r"C:\Users\tianm\Desktop\mode\Qwen3-Embedding-0.6B")
EMBEDDING_BATCH_SIZE = int(_env("EMBEDDING_BATCH_SIZE", "16"))
EMBEDDING_MAX_LENGTH = int(_env("EMBEDDING_MAX_LENGTH", "1024"))

# ============ HierarchicalNodeParser 层级切片 ============
CHUNK_SIZES = [1024, 512, 128]     # root -> parent -> leaf 三级

# ============ Milvus (LlamaIndex 官方连接器) ============
MILVUS_BASE_URL = _env("MILVUS_BASE_URL", "http://127.0.0.1:19530")
MILVUS_COLLECTION = _env("MILVUS_COLLECTION", "rag_nodes")
MILVUS_OVERWRITE = _env("MILVUS_OVERWRITE", "true").lower() == "true"

# ============ MongoDB (docstore: 存全部层级节点含父/祖) ============
MONGO_URI = _env("MONGO_URI", "mongodb://127.0.0.1:27017")
MONGO_DB = _env("MONGO_DB", "multimodal_rag")
MONGO_DOCSTORE_COLLECTION = _env("MONGO_DOCSTORE_COLLECTION", "nodes")

# ============ Elasticsearch (关键词 BM25 检索) ============
ES_URL = _env("ES_URL", "http://127.0.0.1:9200")
ES_INDEX = _env("ES_INDEX", "rag_nodes_bm25")
ES_RETRIEVAL_TOP_K = int(_env("ES_RETRIEVAL_TOP_K", "100"))

# ============ 检索链路: RRF 融合 -> AutoMerge -> Rerank ============
FUSION_TOP_K = int(_env("FUSION_TOP_K", "100"))
RRF_K = int(_env("RRF_K", "60"))
AUTOMERGE_SIMPLE_RATIO_THRESH = float(_env("AUTOMERGE_SIMPLE_RATIO_THRESH", "0.5"))
RERANK_MODEL = _env(
    "RERANK_MODEL", r"C:\Users\tianm\Desktop\mode\bge-reranker-base")  # 容器内 /models/bge-reranker-base
RERANK_TOP_N = int(_env("RERANK_TOP_N", "8"))
QUERY_LLM = None                   # 配置 LLM 实例后可完整问答; None 时用 MockLLM 验证检索链路

# ============ FastAPI 服务 ============
API_HOST = _env("API_HOST", "127.0.0.1")
INGEST_API_PORT = int(_env("INGEST_API_PORT", "8001"))
RETRIEVE_API_PORT = int(_env("RETRIEVE_API_PORT", "8002"))

PROJECT_ROOT = Path(__file__).parent


# ============ 补充: 查询侧指令与默认检索条数 ============
# Qwen3-Embedding 官方推荐查询侧指令(嵌入查询时拼接, 提升检索效果)
QUERY_INSTRUCTION = _env(
    "QUERY_INSTRUCTION",
    "Given a web search query, retrieve relevant passages that answer the query",
)
# 检索 API 默认返回条数(retrieve_api.py / main.py 引用)
RETRIEVAL_TOP_K = int(_env("RETRIEVAL_TOP_K", "100"))
