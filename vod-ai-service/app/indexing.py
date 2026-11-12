"""ES 索引规范与 Milvus collection 管理：视频元数据、音频字幕、视频帧分属不同索引。"""

import logging
import time
from typing import Iterable

from elasticsearch import Elasticsearch, helpers
from pymilvus import (
    Collection,
    CollectionSchema,
    DataType,
    FieldSchema,
    connections,
    utility,
)

from . import config

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------- ES mappings
VIDEO_AI_FIELDS = {
    "aiStatus": {"type": "integer"},
    "aiError": {"type": "keyword", "ignore_above": 256},
    "frameCount": {"type": "integer"},
    "subtitleCount": {"type": "integer"},
    "hasFrame": {"type": "boolean"},
    "hasAsr": {"type": "boolean"},
    "aiUpdatedAt": {"type": "date", "format": "epoch_millis"},
}

SUBTITLE_MAPPING = {
    "settings": {"number_of_shards": 1, "number_of_replicas": 0},
    "mappings": {
        "properties": {
            "videoId": {"type": "long"},
            "userId": {"type": "long"},
            "seq": {"type": "integer"},
            "startMs": {"type": "long"},
            "endMs": {"type": "long"},
            "text": {"type": "text", "analyzer": "standard"},
            "durationMs": {"type": "long"},
            "publishTime": {"type": "date", "format": "epoch_millis||strict_date_optional_time"},
        }
    },
}

FRAME_MAPPING = {
    "settings": {"number_of_shards": 1, "number_of_replicas": 0},
    "mappings": {
        "properties": {
            "videoId": {"type": "long"},
            "userId": {"type": "long"},
            "seq": {"type": "integer"},
            "tsMs": {"type": "long"},
            "bucket": {"type": "keyword"},
            "objectKey": {"type": "keyword"},
            "width": {"type": "integer"},
            "height": {"type": "integer"},
            "publishTime": {"type": "date", "format": "epoch_millis||strict_date_optional_time"},
        }
    },
}


class IndexStore:
    def __init__(self) -> None:
        self.es = Elasticsearch(config.ES_URL, request_timeout=30)
        connections.connect(
            alias="default",
            host=config.MILVUS_HOST,
            port=config.MILVUS_PORT,
        )
        self._frame_collection: Collection | None = None
        self._text_collection: Collection | None = None

    # ------------------------------------------------------------ ES 初始化
    def ensure_es(self) -> None:
        if not self.es.indices.exists(index=config.ES_VIDEO_INDEX):
            self.es.indices.create(
                index=config.ES_VIDEO_INDEX,
                body={
                    "settings": {"number_of_shards": 1, "number_of_replicas": 0},
                    "mappings": {"properties": VIDEO_AI_FIELDS},
                },
            )
        else:
            # 只做增量字段补充，不覆盖已有 mapping
            self.es.indices.put_mapping(
                index=config.ES_VIDEO_INDEX,
                properties=VIDEO_AI_FIELDS,
            )
        if not self.es.indices.exists(index=config.ES_SUBTITLE_INDEX):
            self.es.indices.create(index=config.ES_SUBTITLE_INDEX, body=SUBTITLE_MAPPING)
        if not self.es.indices.exists(index=config.ES_FRAME_INDEX):
            self.es.indices.create(index=config.ES_FRAME_INDEX, body=FRAME_MAPPING)
        logger.info("ES 索引就绪: %s / %s / %s", config.ES_VIDEO_INDEX,
                    config.ES_SUBTITLE_INDEX, config.ES_FRAME_INDEX)

    # -------------------------------------------------------- Milvus 初始化
    def ensure_milvus(self) -> None:
        self._migrate_frame_collection()
        if not utility.has_collection(config.MILVUS_FRAME_COLLECTION):
            schema = CollectionSchema(
                fields=[
                    FieldSchema("id", DataType.VARCHAR, is_primary=True, max_length=64),
                    FieldSchema("videoId", DataType.INT64),
                    FieldSchema("userId", DataType.INT64),
                    FieldSchema("seq", DataType.INT64),
                    FieldSchema("tsMs", DataType.INT64),
                    FieldSchema("publishTime", DataType.INT64),
                    # 元数据：凭向量即可定位到源视频对象（MinIO bucket + object）
                    FieldSchema("bucket", DataType.VARCHAR, max_length=128),
                    FieldSchema("objectKey", DataType.VARCHAR, max_length=512),
                    FieldSchema("vector", DataType.FLOAT_VECTOR, dim=config.CLIP_DIM),
                ],
                description="视频帧 CLIP 向量",
            )
            collection = Collection(config.MILVUS_FRAME_COLLECTION, schema=schema)
            collection.create_index(
                field_name="vector",
                index_params={"index_type": "HNSW", "metric_type": "COSINE",
                              "params": {"M": 16, "efConstruction": 200}},
            )
        if not utility.has_collection(config.MILVUS_TEXT_COLLECTION):
            schema = CollectionSchema(
                fields=[
                    FieldSchema("id", DataType.VARCHAR, is_primary=True, max_length=64),
                    FieldSchema("videoId", DataType.INT64),
                    FieldSchema("userId", DataType.INT64),
                    FieldSchema("seq", DataType.INT64),
                    FieldSchema("startMs", DataType.INT64),
                    FieldSchema("publishTime", DataType.INT64),
                    FieldSchema("vector", DataType.FLOAT_VECTOR, dim=config.TEXT_DIM),
                ],
                description="字幕/文本向量",
            )
            collection = Collection(config.MILVUS_TEXT_COLLECTION, schema=schema)
            collection.create_index(
                field_name="vector",
                index_params={"index_type": "HNSW", "metric_type": "COSINE",
                              "params": {"M": 16, "efConstruction": 200}},
            )
        logger.info("Milvus collection 就绪: %s / %s", config.MILVUS_FRAME_COLLECTION,
                    config.MILVUS_TEXT_COLLECTION)

    def _migrate_frame_collection(self) -> None:
        """旧版本 collection 缺少源视频元数据字段时重建（数据由管线重新写入）。"""
        name = config.MILVUS_FRAME_COLLECTION
        if not utility.has_collection(name):
            return
        fields = {field.name for field in Collection(name).schema.fields}
        if {"bucket", "objectKey"} <= fields:
            return
        logger.warning("Milvus %s 缺少 bucket/objectKey 字段，重建 collection", name)
        utility.drop_collection(name)

    @property
    def frame_collection(self) -> Collection:
        if self._frame_collection is None:
            collection = Collection(config.MILVUS_FRAME_COLLECTION)
            collection.load()
            self._frame_collection = collection
        return self._frame_collection

    @property
    def text_collection(self) -> Collection:
        if self._text_collection is None:
            collection = Collection(config.MILVUS_TEXT_COLLECTION)
            collection.load()
            self._text_collection = collection
        return self._text_collection

    # ------------------------------------------------------------ ES 写入
    def update_video_ai_fields(self, video_id: int, fields: dict) -> None:
        payload = dict(fields)
        payload["id"] = video_id
        payload["aiUpdatedAt"] = int(time.time() * 1000)
        # 主服务同步未覆盖的视频在 ES 里没有文档，用 doc_as_upsert 兜住，避免管线第一步 404 中断
        self.es.update(index=config.ES_VIDEO_INDEX, id=str(video_id),
                       body={"doc": payload, "doc_as_upsert": True}, refresh=True)

    def video_ai_done(self, video_id: int) -> bool:
        """是否已经向量化：ES 状态优先，AI 字段被全量重建覆盖时用 Milvus 向量兜底。"""
        try:
            doc = self.es.get(index=config.ES_VIDEO_INDEX, id=str(video_id))
            if int(doc["_source"].get("aiStatus") or 0) == 2:
                return True
        except Exception:
            pass
        try:
            hits = self.frame_collection.query(
                expr=f"videoId == {video_id}", output_fields=["id"], limit=1)
            return bool(hits)
        except Exception:
            return False

    def index_subtitles(self, docs: Iterable[dict]) -> int:
        actions = [
            {"_index": config.ES_SUBTITLE_INDEX, "_id": doc["id"], "_source": doc}
            for doc in docs
        ]
        if not actions:
            return 0
        success, _ = helpers.bulk(self.es, actions, refresh=True)
        return success

    def index_frames(self, docs: Iterable[dict]) -> int:
        actions = [
            {"_index": config.ES_FRAME_INDEX, "_id": doc["id"], "_source": doc}
            for doc in docs
        ]
        if not actions:
            return 0
        success, _ = helpers.bulk(self.es, actions, refresh=True)
        return success

    # --------------------------------------------------------- Milvus 写入
    def insert_vectors(self, video_id: int, frames: list[dict], subtitles: list[dict]) -> None:
        frame_collection = self.frame_collection
        text_collection = self.text_collection

        if frames:
            frame_collection.insert(
                [
                    [f"{video_id}_{item['seq']}" for item in frames],
                    [video_id] * len(frames),
                    [item["userId"] for item in frames],
                    [item["seq"] for item in frames],
                    [item["tsMs"] for item in frames],
                    [item["publishTime"] for item in frames],
                    [item.get("bucket") or "" for item in frames],
                    [item.get("objectKey") or "" for item in frames],
                    [item["vector"].tolist() for item in frames],
                ]
            )
        if subtitles:
            text_collection.insert(
                [
                    [f"{video_id}_{item['seq']}" for item in subtitles],
                    [video_id] * len(subtitles),
                    [item["userId"] for item in subtitles],
                    [item["seq"] for item in subtitles],
                    [item["startMs"] for item in subtitles],
                    [item["publishTime"] for item in subtitles],
                    [item["vector"].tolist() for item in subtitles],
                ]
            )
        frame_collection.flush()
        text_collection.flush()

    def delete_video_vectors(self, video_id: int) -> None:
        self.frame_collection.delete(f"videoId == {video_id}")
        self.text_collection.delete(f"videoId == {video_id}")
        self.es.delete_by_query(
            index=config.ES_SUBTITLE_INDEX,
            body={"query": {"term": {"videoId": video_id}}},
            refresh=True,
            ignore_unavailable=True,
        )
        self.es.delete_by_query(
            index=config.ES_FRAME_INDEX,
            body={"query": {"term": {"videoId": video_id}}},
            refresh=True,
            ignore_unavailable=True,
        )
