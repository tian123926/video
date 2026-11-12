"""vod-ai-service：视频理解（抽帧/CLIP/ASR）与混合检索服务。"""

import asyncio
import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI
from pydantic import BaseModel, Field

from . import config
from .models import registry
from .pipeline import list_pending_videos, pipeline
from .search import HybridSearch

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger("vod-ai")

_startup_error = ""


def _bootstrap() -> None:
    global _startup_error
    for attempt in range(1, 31):
        try:
            pipeline.ensure_ready()
            logger.info("索引与 collection 初始化完成")
            _preload_models()
            return
        except Exception as exc:
            _startup_error = f"{type(exc).__name__}: {exc}"
            logger.warning("初始化失败(%s/30): %s", attempt, _startup_error)
            time.sleep(3)


def _preload_models() -> None:
    """预热检索链路用到的模型，避免首个搜索请求因冷加载超时。"""
    try:
        registry.clip_text_embedding("预热")
        registry.text_embeddings(["预热"])
        logger.info("检索模型预热完成")
    except Exception as exc:
        logger.warning("检索模型预热失败: %s", exc)


@asynccontextmanager
async def lifespan(_: FastAPI):
    # 同步完成索引初始化与模型预热后再对外服务，避免首个请求被冷加载拖住
    await asyncio.to_thread(_bootstrap)
    yield


app = FastAPI(title="vod-ai-service", version="1.0.0", lifespan=lifespan)
search_service = HybridSearch(pipeline.store)


class ProcessRequest(BaseModel):
    videoId: int
    force: bool = False


class PendingRequest(BaseModel):
    limit: int = Field(default=20, ge=1, le=200)


class SearchRequest(BaseModel):
    keyword: str
    cursor: str | None = None
    size: int = Field(default=config.SEARCH_PAGE_SIZE, ge=1, le=50)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "startupError": _startup_error or None,
        "models": {
            "clip": config.CLIP_MODEL_DIR,
            "asr": config.ASR_MODEL_DIR,
            "text": config.TEXT_MODEL_DIR,
        },
    }


@app.post("/ai/process")
def process(request: ProcessRequest) -> dict:
    accepted = pipeline.submit(request.videoId, force=request.force)
    return {"accepted": accepted, "videoId": request.videoId}


@app.post("/ai/process/sync")
def process_sync(request: ProcessRequest) -> dict:
    return pipeline.process_video(request.videoId, force=request.force)


@app.post("/ai/process/pending")
def process_pending(request: PendingRequest) -> dict:
    ids = list_pending_videos(request.limit)
    accepted = [video_id for video_id in ids if pipeline.submit(video_id)]
    return {"candidates": ids, "accepted": accepted}


@app.post("/ai/search")
def search(request: SearchRequest) -> dict:
    return search_service.search(request.keyword, request.cursor, request.size)
