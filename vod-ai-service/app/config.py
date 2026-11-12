import os


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _int(name: str, default: int) -> int:
    try:
        return int(_env(name, str(default)))
    except ValueError:
        return default


def _float(name: str, default: float) -> float:
    try:
        return float(_env(name, str(default)))
    except ValueError:
        return default


# ---- 基础组件 ----
ES_URL = _env("ELASTICSEARCH_URL", "http://elasticsearch:9200")
ES_VIDEO_INDEX = _env("VOD_VIDEO_INDEX", "vod_video")
ES_SUBTITLE_INDEX = _env("VOD_SUBTITLE_INDEX", "vod_video_subtitle")
ES_FRAME_INDEX = _env("VOD_FRAME_INDEX", "vod_video_frame")

MILVUS_HOST = _env("MILVUS_HOST", "milvus")
MILVUS_PORT = _env("MILVUS_PORT", "19530")
MILVUS_FRAME_COLLECTION = _env("MILVUS_FRAME_COLLECTION", "vod_frame_vec")
MILVUS_TEXT_COLLECTION = _env("MILVUS_TEXT_COLLECTION", "vod_text_vec")

MINIO_ENDPOINT = _env("MINIO_ENDPOINT", "http://minio:9000")
# HLS/DASH 分片桶：无原始源文件时从这里合并分片再向量化
HLS_BUCKET = _env("VOD_HLS_BUCKET", "video-hls")
MINIO_ACCESS_KEY = _env("MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET_KEY = _env("MINIO_SECRET_KEY", "minioadmin")
MINIO_SECURE = _env("MINIO_SECURE", "false").lower() == "true"

MYSQL_HOST = _env("MYSQL_HOST", "mysql")
MYSQL_PORT = _int("MYSQL_PORT", 3306)
MYSQL_DATABASE = _env("MYSQL_DATABASE", "video_test_one")
MYSQL_USERNAME = _env("MYSQL_USERNAME", "root")
MYSQL_PASSWORD = _env("MYSQL_PASSWORD", "root")

# ---- 模型 ----
MODEL_ROOT = _env("MODEL_ROOT", "/app/models")
CLIP_MODEL_DIR = _env("CLIP_MODEL_DIR", os.path.join(MODEL_ROOT, "chinese-clip-vit-b16"))
ASR_MODEL_DIR = _env("ASR_MODEL_DIR", os.path.join(MODEL_ROOT, "paraformer-large-vad-punc"))
TEXT_MODEL_DIR = _env("TEXT_MODEL_DIR", os.path.join(MODEL_ROOT, "gte-zh-base"))
DEVICE = _env("VOD_AI_DEVICE", "cpu")
CLIP_DIM = _int("CLIP_DIM", 512)
TEXT_DIM = _int("TEXT_DIM", 768)

# ---- 处理管线 ----
WORK_DIR = _env("VOD_AI_WORK_DIR", "/tmp/vod-ai")
FRAME_INTERVAL_SEC = _float("FRAME_INTERVAL_SEC", 5.0)
FRAME_MAX = _int("FRAME_MAX", 200)
FRAME_WIDTH = _int("FRAME_WIDTH", 360)
ASR_ENABLED = _env("ASR_ENABLED", "false").lower() in ("1", "true", "yes", "on")
# CPU 推理线程数：与 limits.cpu 保持一致，避免线程切换开销
TORCH_THREADS = _int("TORCH_THREADS", 4)
# 查询向量 / 意图判定缓存条目上限，避免重复编码
EMBED_CACHE_SIZE = _int("EMBED_CACHE_SIZE", 1024)
ASR_MAX_SECONDS = _int("ASR_MAX_SECONDS", 1800)
SUBTITLE_MIN_CHARS = _int("SUBTITLE_MIN_CHARS", 2)

# ---- 检索 ----
SEARCH_TOP_K = _int("SEARCH_TOP_K", 50)
SEARCH_MAX_TOP_K = _int("SEARCH_MAX_TOP_K", 300)
SEARCH_PAGE_SIZE = _int("SEARCH_PAGE_SIZE", 8)
# 最低置信度阈值：0 表示不做过滤（默认关闭，避免弱相关结果被整体丢弃）
SEARCH_MIN_SCORE = _float("SEARCH_MIN_SCORE", 0.0)
SEARCH_USER_MAX_HITS = _int("SEARCH_USER_MAX_HITS", 2)
SEARCH_USER_MAX_HITS_TOTAL = _int("SEARCH_USER_MAX_HITS_TOTAL", 3)
WEIGHT_FRAME = _float("SEARCH_WEIGHT_FRAME", 0.55)
WEIGHT_TEXT_VEC = _float("SEARCH_WEIGHT_TEXT_VEC", 0.30)
WEIGHT_ES = _float("SEARCH_WEIGHT_ES", 0.15)
