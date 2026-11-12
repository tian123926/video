"""视频 AI 处理管线：源文件 -> 音频字幕 -> 视频帧 -> 向量化 -> ES/Milvus。"""

import logging
import os
import shutil
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pymysql
from minio import Minio
from minio.error import S3Error

from . import config, media
from .indexing import IndexStore
from .models import registry

logger = logging.getLogger(__name__)

_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="vod-ai")
_inflight: set[int] = set()
_lock = threading.Lock()


def _db_connect():
    return pymysql.connect(
        host=config.MYSQL_HOST,
        port=config.MYSQL_PORT,
        user=config.MYSQL_USERNAME,
        password=config.MYSQL_PASSWORD,
        database=config.MYSQL_DATABASE,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=True,
    )


def fetch_video(video_id: int) -> dict | None:
    with _db_connect() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT id, user_id, title, duration_ms, source_bucket, source_object, "
                "publish_time, status, deleted FROM t_video WHERE id = %s",
                (video_id,),
            )
            return cursor.fetchone()


def list_pending_videos(limit: int = 20) -> list[int]:
    with _db_connect() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT id FROM t_video WHERE status = 3 AND deleted = 0 "
                "ORDER BY id DESC LIMIT %s",
                (limit,),
            )
            return [row["id"] for row in cursor.fetchall()]


def _minio_client() -> Minio:
    endpoint = config.MINIO_ENDPOINT.replace("http://", "").replace("https://", "")
    return Minio(
        endpoint,
        access_key=config.MINIO_ACCESS_KEY,
        secret_key=config.MINIO_SECRET_KEY,
        secure=config.MINIO_SECURE,
    )


def _publish_ms(video: dict) -> int:
    value = video.get("publish_time")
    if value is None:
        return int(time.time() * 1000)
    return int(value.timestamp() * 1000)


_hls_prefix_cache: dict[str, str] = {}


def _hls_prefix(client: Minio, source_object: str) -> str | None:
    """bilibili/BVxxxx -> {mid}/BVxxxx，即 video-hls 下的 DASH 分片目录。"""
    name = os.path.basename(source_object.strip("/"))
    if not name:
        return None
    if name in _hls_prefix_cache:
        return _hls_prefix_cache[name] or None
    roots = [
        item.object_name.rstrip("/")
        for item in client.list_objects(config.HLS_BUCKET, recursive=False)
        if item.is_dir
    ]
    for root in roots:
        candidate = f"{root}/{name}"
        try:
            client.stat_object(config.HLS_BUCKET, f"{candidate}/video/init.mp4")
        except S3Error:
            continue
        _hls_prefix_cache[name] = candidate
        return candidate
    _hls_prefix_cache[name] = ""
    return None


def _concat_ts(client: Minio, video_id: int, target: str) -> bool:
    """兜底：hls/{videoId}/seg-*.ts 直接按序号拼接（TS 可直接串接）。"""
    prefix = f"hls/{video_id}/"
    names = [
        item.object_name
        for item in client.list_objects(config.HLS_BUCKET, prefix=prefix, recursive=True)
        if item.object_name.endswith(".ts")
    ]
    if not names:
        return False

    def order(name: str) -> int:
        digits = "".join(ch for ch in os.path.basename(name) if ch.isdigit())
        return int(digits or 0)

    with open(target, "wb") as handle:
        for name in sorted(names, key=order):
            with client.get_object(config.HLS_BUCKET, name) as response:
                shutil.copyfileobj(response, handle)
    return True


class Pipeline:
    def __init__(self) -> None:
        self.store = IndexStore()
        self._ready = False

    def ensure_ready(self) -> None:
        if self._ready:
            return
        self.store.ensure_es()
        self.store.ensure_milvus()
        self._ready = True

    # -------------------------------------------------------------- 任务调度
    def submit(self, video_id: int, force: bool = False) -> bool:
        with _lock:
            if video_id in _inflight:
                return False
            _inflight.add(video_id)
        _executor.submit(self._run_safe, video_id, force)
        return True

    def _run_safe(self, video_id: int, force: bool) -> None:
        try:
            self.process_video(video_id, force=force)
        except Exception as exc:  # 管线异常只记录，不影响服务
            logger.exception("视频 %s 处理失败", video_id)
            try:
                self.store.update_video_ai_fields(
                    video_id, {"aiStatus": 3, "aiError": f"{type(exc).__name__}: {exc}"[:250]}
                )
            except Exception:
                logger.warning("写入失败状态时出错 video=%s", video_id)
        finally:
            with _lock:
                _inflight.discard(video_id)

    # ---------------------------------------------------------------- 主流程
    def process_video(self, video_id: int, force: bool = False) -> dict:
        self.ensure_ready()
        video = fetch_video(video_id)
        if not video:
            return {"videoId": video_id, "status": "missing"}
        if video["status"] != 3 or video["deleted"]:
            return {"videoId": video_id, "status": "skipped", "reason": "not playable"}

        if not force and self.store.video_ai_done(video_id):
            return {"videoId": video_id, "status": "skipped", "reason": "already processed"}

        self.store.update_video_ai_fields(video_id, {"aiStatus": 1, "aiError": ""})
        # 先清掉旧版本的字幕/帧索引（Milvus 向量 + ES 文档），再写入新数据，
        # 否则新写入的 ES 帧文档会被这一步一并删掉
        self.store.delete_video_vectors(video_id)

        work_dir = os.path.join(config.WORK_DIR, str(video_id))
        if os.path.isdir(work_dir):
            shutil.rmtree(work_dir, ignore_errors=True)
        os.makedirs(work_dir, exist_ok=True)

        started = time.time()
        source_path = os.path.join(work_dir, "source")
        try:
            self._fetch_source(_minio_client(), video, source_path)
            duration_ms = video.get("duration_ms") or media.probe_duration_ms(source_path)
            publish_ms = _publish_ms(video)
            user_id = int(video["user_id"])

            subtitle_count, text_vectors = self._process_audio(
                video_id, user_id, publish_ms, duration_ms, source_path, work_dir
            )
            frame_count, frame_vectors = self._process_frames(
                video_id, user_id, publish_ms, source_path, work_dir,
                video["source_bucket"], video["source_object"],
            )
            # 向量写入放在处理完成之后，避免中途失败导致旧向量已被清空
            self.store.insert_vectors(video_id, frame_vectors, text_vectors)

            self.store.update_video_ai_fields(
                video_id,
                {
                    "aiStatus": 2,
                    "frameCount": frame_count,
                    "subtitleCount": subtitle_count,
                    "hasFrame": frame_count > 0,
                    "hasAsr": subtitle_count > 0,
                },
            )
            return {
                "videoId": video_id,
                "status": "ok",
                "frameCount": frame_count,
                "subtitleCount": subtitle_count,
                "costMs": int((time.time() - started) * 1000),
            }
        finally:
            shutil.rmtree(work_dir, ignore_errors=True)

    # -------------------------------------------------------------- 源文件获取
    def _fetch_source(self, client: Minio, video: dict, target: str) -> None:
        """优先取原始源文件；源文件缺失时回退到 HLS/DASH 分片合并。"""
        bucket = video["source_bucket"]
        key = video["source_object"]
        try:
            client.fget_object(bucket, key, target)
            return
        except S3Error as exc:
            if exc.code not in ("NoSuchKey", "NoSuchBucket"):
                raise
            logger.warning("源文件缺失 %s/%s，改用 HLS 分片合并", bucket, key)
        prefix = _hls_prefix(client, key)
        if prefix:
            media.merge_dash(client, prefix, os.path.dirname(target), target)
            return
        if _concat_ts(client, int(video["id"]), target):
            return
        raise RuntimeError(f"源文件与 HLS 分片均不可用: {key}")

    # -------------------------------------------------------------- 音频/字幕
    def _process_audio(self, video_id: int, user_id: int, publish_ms: int,
                       duration_ms: int, source_path: str, work_dir: str):
        if not config.ASR_ENABLED:
            # 语音字幕链路暂时关闭（ASR 切句依赖标点模型，待后续调试）
            return 0, []
        wav_path = os.path.join(work_dir, "audio.wav")
        try:
            media.extract_audio(source_path, wav_path, max_seconds=config.ASR_MAX_SECONDS)
            segments = registry.transcribe(wav_path)
        except Exception as exc:
            logger.warning("视频 %s 音频处理失败: %s", video_id, exc)
            return 0, []
        finally:
            # ASR 是内存占用最大的模型，索引完成后立即卸载，避免与 CLIP 同时常驻
            registry.release("asr")
        if not segments:
            return 0, []

        texts = [item["text"] for item in segments]
        vectors = registry.text_embeddings(texts)

        subtitle_docs = []
        vectors_payload = []
        for index, item in enumerate(segments):
            seq = index
            subtitle_docs.append(
                {
                    "id": f"{video_id}_{seq}",
                    "videoId": video_id,
                    "userId": user_id,
                    "seq": seq,
                    "startMs": item["start_ms"],
                    "endMs": item["end_ms"],
                    "text": item["text"],
                    "durationMs": duration_ms,
                    "publishTime": publish_ms,
                }
            )
            vectors_payload.append(
                {
                    "seq": seq,
                    "startMs": item["start_ms"],
                    "userId": user_id,
                    "publishTime": publish_ms,
                    "vector": vectors[index],
                }
            )
        self.store.index_subtitles(subtitle_docs)
        return len(subtitle_docs), vectors_payload

    # ---------------------------------------------------------------- 视频帧
    def _process_frames(self, video_id: int, user_id: int, publish_ms: int,
                        source_path: str, work_dir: str,
                        source_bucket: str = "", source_object: str = ""):
        frame_dir = os.path.join(work_dir, "frames")
        try:
            frames = media.extract_frames(
                source_path, frame_dir, config.FRAME_INTERVAL_SEC, config.FRAME_MAX
            )
        except Exception as exc:
            logger.warning("视频 %s 抽帧失败: %s", video_id, exc)
            return 0, []
        if not frames:
            return 0, []
        try:
            vectors = registry.clip_image_embeddings([item["path"] for item in frames])
        except Exception as exc:
            logger.warning("视频 %s 帧向量化失败: %s", video_id, exc)
            return 0, []

        frame_docs = []
        vectors_payload = []
        for index, item in enumerate(frames):
            frame_docs.append(
                {
                    "id": f"{video_id}_{item['seq']}",
                    "videoId": video_id,
                    "userId": user_id,
                    "seq": item["seq"],
                    "tsMs": item["ts_ms"],
                    "bucket": source_bucket,
                    "objectKey": source_object,
                    "publishTime": publish_ms,
                }
            )
            vectors_payload.append(
                {
                    "seq": item["seq"],
                    "tsMs": item["ts_ms"],
                    "userId": user_id,
                    "publishTime": publish_ms,
                    "bucket": source_bucket,
                    "objectKey": source_object,
                    "vector": vectors[index],
                }
            )
        self.store.index_frames(frame_docs)
        return len(frame_docs), vectors_payload


pipeline = Pipeline()
