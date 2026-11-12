"""混合检索：意图判定 -> 关键词/三路召回 -> 归一化融合 -> 置信度过滤 -> 同作者限流 -> 游标分页。"""

import base64
import hashlib
import json
import logging
import threading

import numpy as np

from . import config
from .indexing import IndexStore
from .models import registry

logger = logging.getLogger(__name__)

KEYWORD_SUFFIX_HINTS = (
    "的", "了", "吗", "怎么", "如何", "为什么", "哪些", "哪个", "有没有", "能不能",
    "是什么", "介绍", "讲的是", "视频里", "我想看", "那个", "这个", "求",
)

DESCRIPTION_SEEDS = [
    "一个男人在厨房里做饭",
    "视频里的人在讲如何学习编程",
]


def _normalize_scores(scores: dict[int, float]) -> dict[int, float]:
    if not scores:
        return {}
    # 用「除以最大值」而不是 min-max：min-max 会把最后一名压成 0，导致多结果场景下
    # 除首条外全部被 MIN_SCORE 过滤掉
    high = max(scores.values())
    if high <= 1e-9:
        return {key: 0.0 for key in scores}
    return {key: max(0.0, min(1.0, value / high)) for key, value in scores.items()}


class HybridSearch:
    def __init__(self, store: IndexStore) -> None:
        self.store = store
        self._intent_cache: dict[str, str] = {}
        self._seed_vectors: np.ndarray | None = None
        self._seed_lock = threading.Lock()

    def _seeds(self) -> np.ndarray:
        """种子句向量只编码一次，意图判定时只需编码 query 本身。"""
        if self._seed_vectors is None:
            with self._seed_lock:
                if self._seed_vectors is None:
                    self._seed_vectors = registry.text_embeddings(DESCRIPTION_SEEDS)
        return self._seed_vectors

    # ------------------------------------------------------------ 意图判定
    def classify_intent(self, query: str) -> str:
        text = query.strip()
        cached = self._intent_cache.get(text)
        if cached:
            return cached
        intent = self._classify(text)
        if len(self._intent_cache) >= config.EMBED_CACHE_SIZE:
            self._intent_cache.clear()
        self._intent_cache[text] = intent
        return intent

    def _classify(self, text: str) -> str:
        # 极短查询（如「原神」「的」）一定是关键词，直接跳过模型判定，省掉冷加载
        if len(text) <= 2:
            return "keyword"
        if len(text) >= 12:
            return "description"
        if any(hint in text for hint in KEYWORD_SUFFIX_HINTS):
            return "description"
        if " " in text or "，" in text or "。" in text:
            return "description"
        try:
            vector = registry.text_embeddings([text])[0]
            if float(np.max(self._seeds() @ vector)) >= 0.62:
                return "description"
        except Exception as exc:
            logger.debug("意图判定模型不可用，回退关键词: %s", exc)
        return "keyword"

    # ------------------------------------------------------------ ES 召回
    def _es_search(self, query: str, limit: int,
                   operator: str = "and") -> tuple[dict[int, float], int]:
        scores: dict[int, float] = {}
        matched_total = 0
        try:
            must = [{
                "multi_match": {
                    "query": query,
                    "fields": ["title^3", "authorName^2", "description"],
                    "type": "best_fields",
                    "operator": operator,
                    "minimum_should_match": "80%" if operator == "and" else "30%",
                }
            }] if query else []
            video_res = self.store.es.search(
                index=config.ES_VIDEO_INDEX,
                size=limit,
                # 只需命中信息，关闭全量总数统计与多余字段，显著降低查询耗时
                track_total_hits=1000,
                request_cache=True,
                source=["id"],
                query={
                    "bool": {
                        "filter": [{"term": {"status": 3}}, {"term": {"deleted": 0}}],
                        "must": must,
                    }
                },
            )
            for hit in video_res["hits"]["hits"]:
                video_id = int(hit["_source"]["id"])
                scores[video_id] = max(scores.get(video_id, 0.0), float(hit["_score"]))
            matched_total = int(video_res["hits"]["total"]["value"])
        except Exception as exc:
            logger.warning("视频索引检索失败: %s", exc)

        try:
            subtitle_res = self.store.es.search(
                index=config.ES_SUBTITLE_INDEX,
                size=limit,
                track_total_hits=10,
                request_cache=True,
                source=["videoId"],
                query={"match": {"text": query}},
            )
            for hit in subtitle_res["hits"]["hits"]:
                video_id = int(hit["_source"]["videoId"])
                score = float(hit["_score"]) * 0.9
                scores[video_id] = max(scores.get(video_id, 0.0), score)
        except Exception as exc:
            logger.warning("字幕索引检索失败: %s", exc)
        return scores, matched_total

    # --------------------------------------------------------- 向量召回
    def _vector_search(self, collection, vector, limit: int, output_fields: list[str]) -> list[dict]:
        hits = collection.search(
            data=[vector.tolist()],
            anns_field="vector",
            param={"metric_type": "COSINE", "params": {"ef": max(64, limit)}},
            limit=limit,
            output_fields=output_fields,
        )
        results = []
        for hit in hits[0]:
            results.append(
                {
                    "videoId": int(hit.entity.get("videoId")),
                    "userId": int(hit.entity.get("userId")),
                    "seq": int(hit.entity.get("seq")),
                    "tsMs": int(hit.entity.get("tsMs") or 0),
                    "startMs": int(hit.entity.get("startMs") or 0),
                    "score": float(hit.score),
                }
            )
        return results

    def _aggregate_hits(self, hits: list[dict]) -> dict[int, dict]:
        aggregated: dict[int, dict] = {}
        for hit in hits:
            video_id = hit["videoId"]
            slot = aggregated.setdefault(
                video_id,
                {"max": 0.0, "count": 0, "userId": hit["userId"], "best": hit},
            )
            slot["count"] += 1
            if hit["score"] > slot["max"]:
                slot["max"] = hit["score"]
                slot["best"] = hit
        # 同一视频多次命中给予少量加成
        for slot in aggregated.values():
            slot["score"] = slot["max"] * (1.0 + 0.02 * min(slot["count"], 5))
        return aggregated

    # ------------------------------------------------------------ 主入口
    def search(self, query: str, cursor: str | None, size: int) -> dict:
        query = (query or "").strip()
        size = max(1, min(size or config.SEARCH_PAGE_SIZE, 50))
        if not query:
            return {"items": [], "total": 0, "hasMore": False, "cursor": None,
                    "intent": "keyword", "source": "empty"}

        intent = self.classify_intent(query)
        offset, query_hash = self._decode_cursor(cursor, query)

        limit = config.SEARCH_TOP_K
        # 关键词场景依赖 ES 相关性（operator=and），不做置信度过滤；
        # 只有自然语言描述场景才用相似度阈值筛掉弱相关结果
        threshold = config.SEARCH_MIN_SCORE if intent == "description" else 0.0
        fused: dict[int, dict] = {}
        while True:
            fused, es_total = self._recall(query, intent, limit)
            # 多取 1 条用于判断是否还有下一页
            ranked = self._post_process(fused, offset + size + 1, threshold)
            exhausted = len(fused) < limit
            if len(ranked) >= offset + size + 1 or exhausted or limit >= config.SEARCH_MAX_TOP_K:
                break
            limit = min(limit + 50, config.SEARCH_MAX_TOP_K)

        page = ranked[offset:offset + size]
        fallback = False
        if not page and offset == 0:
            # 首屏无结果时用 ES 宽松匹配兜底，避免自然语言查询被误判为关键词后直接返回空列表
            relaxed, _ = self._es_search(query, limit, operator="or")
            if relaxed:
                relaxed_fused = {
                    video_id: {"score": score, "routes": ["es"]}
                    for video_id, score in relaxed.items()
                }
                ranked = self._post_process(relaxed_fused, offset + size + 1, 0.0)
                page = ranked[offset:offset + size]
                fallback = True
        has_more = len(ranked) > offset + size
        next_cursor = self._encode_cursor(offset + size, query_hash) if has_more else None

        items = self._attach_video_meta(page)
        return {
            "items": items,
            "total": max(es_total, len(ranked)),
            "hasMore": has_more,
            "cursor": next_cursor,
            "intent": intent,
            "source": "es-fallback" if fallback else ("hybrid" if intent == "description" else "es"),
        }

    def _recall(self, query: str, intent: str, limit: int) -> tuple[dict[int, dict], int]:
        routes: dict[str, dict[int, float]] = {}
        es_scores, es_total = self._es_search(query, limit)
        routes["es"] = es_scores

        if intent == "description":
            try:
                frame_hits = self._vector_search(
                    self.store.frame_collection, registry.clip_text_embedding(query), limit,
                    ["videoId", "userId", "seq", "tsMs", "bucket", "objectKey"],
                )
                routes["frame"] = {vid: slot["score"]
                                   for vid, slot in self._aggregate_hits(frame_hits).items()}
            except Exception as exc:
                logger.warning("帧向量召回失败: %s", exc)
            try:
                text_hits = self._vector_search(
                    self.store.text_collection, registry.text_embeddings([query])[0], limit,
                    ["videoId", "userId", "seq", "startMs"],
                )
                routes["text"] = {vid: slot["score"]
                                  for vid, slot in self._aggregate_hits(text_hits).items()}
            except Exception as exc:
                logger.warning("文本向量召回失败: %s", exc)

        weights = {
            "frame": config.WEIGHT_FRAME,
            "text": config.WEIGHT_TEXT_VEC,
            "es": config.WEIGHT_ES,
        }
        # 只对本次实际召回的路径做权重归一，避免关键词场景下分数被稀释到阈值以下
        active_weight = sum(weights.get(route, 0.0) for route in routes) or 1.0
        fused: dict[int, dict] = {}
        for route, scores in routes.items():
            normalized = _normalize_scores(scores)
            weight = weights.get(route, 0.0) / active_weight
            for video_id, score in normalized.items():
                slot = fused.setdefault(video_id, {"score": 0.0, "routes": []})
                slot["score"] += weight * score
                slot["routes"].append(route)
        return fused, es_total

    def _post_process(self, fused: dict[int, dict], needed: int,
                      min_score: float | None = None) -> list[dict]:
        if min_score is None:
            min_score = config.SEARCH_MIN_SCORE
        candidates = [
            {"videoId": vid, **slot}
            for vid, slot in fused.items()
            if slot["score"] >= min_score
        ]
        candidates.sort(key=lambda item: item["score"], reverse=True)

        authors = self._video_authors([item["videoId"] for item in candidates])
        author_counts: dict[int, int] = {}
        selected: list[dict] = []
        for item in candidates:
            author = authors.get(item["videoId"], 0)
            item["userId"] = author
            limit = (config.SEARCH_USER_MAX_HITS if len(selected) < config.SEARCH_PAGE_SIZE
                     else config.SEARCH_USER_MAX_HITS_TOTAL)
            if author and author_counts.get(author, 0) >= limit:
                continue
            author_counts[author] = author_counts.get(author, 0) + 1
            selected.append(item)
            if len(selected) >= needed:
                break
        return selected

    def _video_authors(self, video_ids: list[int]) -> dict[int, int]:
        if not video_ids:
            return {}
        try:
            result = self.store.es.mget(index=config.ES_VIDEO_INDEX,
                                        ids=[str(vid) for vid in video_ids])
            owners: dict[int, int] = {}
            for doc in result["docs"]:
                if doc.get("found"):
                    owners[int(doc["_id"])] = int(doc["_source"].get("userId") or 0)
            return owners
        except Exception as exc:
            logger.warning("回捞视频作者失败: %s", exc)
            return {}

    def _attach_video_meta(self, page: list[dict]) -> list[dict]:
        if not page:
            return []
        ids = [str(item["videoId"]) for item in page]
        meta: dict[str, dict] = {}
        try:
            result = self.store.es.mget(index=config.ES_VIDEO_INDEX, ids=ids)
            for doc in result["docs"]:
                if doc.get("found"):
                    meta[doc["_id"]] = doc["_source"]
        except Exception as exc:
            logger.warning("回捞视频元数据失败: %s", exc)

        items = []
        for item in page:
            source = meta.get(str(item["videoId"]), {})
            items.append(
                {
                    "videoId": item["videoId"],
                    "userId": source.get("userId") or item.get("userId") or 0,
                    "title": source.get("title"),
                    "authorName": source.get("authorName"),
                    "coverBucket": source.get("coverBucket"),
                    "coverObject": source.get("coverObject"),
                    "viewCount": source.get("viewCount"),
                    "publishTime": source.get("publishTime"),
                    "durationMs": source.get("durationMs"),
                    "score": round(item["score"], 4),
                    "routes": item["routes"],
                }
            )
        return items

    # -------------------------------------------------------------- 游标
    @staticmethod
    def _query_hash(query: str) -> str:
        return hashlib.md5(query.encode("utf-8")).hexdigest()[:12]

    def _encode_cursor(self, offset: int, query_hash: str) -> str:
        raw = json.dumps({"o": offset, "q": query_hash}, separators=(",", ":"))
        return base64.urlsafe_b64encode(raw.encode("utf-8")).decode("ascii")

    def _decode_cursor(self, cursor: str | None, query: str) -> tuple[int, str]:
        query_hash = self._query_hash(query)
        if not cursor:
            return 0, query_hash
        try:
            raw = json.loads(base64.urlsafe_b64decode(cursor.encode("ascii")).decode("utf-8"))
            if raw.get("q") != query_hash:
                return 0, query_hash
            return max(0, int(raw.get("o") or 0)), query_hash
        except Exception:
            return 0, query_hash
