"""模型加载与推理封装：中文 CLIP（帧向量）、Paraformer（ASR）、GTE（文本向量）。"""

import gc
import logging
import os
import threading

import numpy as np

from . import config

logger = logging.getLogger(__name__)


def _to_numpy(value) -> np.ndarray:
    if hasattr(value, "detach"):
        value = value.detach().cpu().numpy()
    array = np.asarray(value, dtype=np.float32)
    if array.ndim == 1:
        array = array.reshape(1, -1)
    return array


def _normalize(array: np.ndarray) -> np.ndarray:
    norm = np.linalg.norm(array, axis=1, keepdims=True)
    norm[norm == 0] = 1.0
    return (array / norm).astype(np.float32)


_TORCH_READY = False
_DEVICE: str | None = None


def _setup_torch() -> None:
    """固定推理线程数，避免多请求争抢线程导致上下文切换开销。"""
    global _TORCH_READY
    if _TORCH_READY:
        return
    import torch

    torch.set_num_threads(config.TORCH_THREADS)
    try:
        torch.set_num_interop_threads(1)
    except RuntimeError:
        pass
    _TORCH_READY = True


def torch_device() -> str:
    """实际推理设备：配置为 cuda 但显卡不可用时自动回退 CPU。"""
    global _DEVICE
    if _DEVICE is None:
        _DEVICE = "cpu"
        if config.DEVICE.startswith("cuda"):
            import torch

            if torch.cuda.is_available():
                _DEVICE = config.DEVICE
            else:
                logger.warning("未检测到可用 CUDA，回退 CPU 推理")
    return _DEVICE


def modelscope_device() -> str:
    """modelscope pipeline 只认 cpu / gpu。"""
    return "gpu" if torch_device() != "cpu" else "cpu"


class ModelRegistry:
    """模型按需加载，避免服务启动时占用过多内存。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._clip = None
        self._asr = None
        self._text = None
        # 查询向量缓存：同一 query 重复检索直接命中，避免重复前向计算
        self._clip_text_cache: dict[str, np.ndarray] = {}
        self._text_cache: dict[str, np.ndarray] = {}

    def _require_dir(self, path: str, name: str) -> None:
        if not os.path.isdir(path) or not os.listdir(path):
            raise RuntimeError(f"{name} 模型目录不存在或为空: {path}")

    @property
    def clip(self):
        if self._clip is None:
            with self._lock:
                if self._clip is None:
                    self._require_dir(config.CLIP_MODEL_DIR, "CLIP")
                    from transformers import ChineseCLIPModel, ChineseCLIPProcessor

                    logger.info("loading CLIP model from %s", config.CLIP_MODEL_DIR)
                    # 使用 transformers 版中文 CLIP，避免 modelscope 版依赖难以编译的 fairseq
                    model = ChineseCLIPModel.from_pretrained(config.CLIP_MODEL_DIR).eval()
                    processor = ChineseCLIPProcessor.from_pretrained(config.CLIP_MODEL_DIR)
                    model = model.to(torch_device())
                    logger.info("CLIP 推理设备: %s", torch_device())
                    self._clip = (model, processor)
        return self._clip

    @property
    def asr(self):
        if self._asr is None:
            with self._lock:
                if self._asr is None:
                    self._require_dir(config.ASR_MODEL_DIR, "ASR")
                    from modelscope.pipelines import pipeline
                    from modelscope.utils.constant import Tasks

                    logger.info("loading ASR model from %s", config.ASR_MODEL_DIR)
                    self._asr = pipeline(
                        task=Tasks.auto_speech_recognition,
                        model=config.ASR_MODEL_DIR,
                        device=modelscope_device(),
                    )
        return self._asr

    @property
    def text(self):
        if self._text is None:
            with self._lock:
                if self._text is None:
                    self._require_dir(config.TEXT_MODEL_DIR, "TEXT")
                    from modelscope.pipelines import pipeline
                    from modelscope.utils.constant import Tasks

                    logger.info("loading text embedding model from %s", config.TEXT_MODEL_DIR)
                    self._text = pipeline(
                        task=Tasks.sentence_embedding,
                        model=config.TEXT_MODEL_DIR,
                        device=modelscope_device(),
                    )
        return self._text

    # ---- 显存/内存回收 ----
    def release(self, *names: str) -> None:
        """用完即卸载模型，降低 CPU 推理时的常驻内存峰值。"""
        with self._lock:
            for name in names:
                attribute = f"_{name}"
                if getattr(self, attribute, None) is not None:
                    setattr(self, attribute, None)
                    logger.info("released %s model", name)
        gc.collect()

    # ---- CLIP ----
    def clip_text_embedding(self, text: str) -> np.ndarray:
        cached = self._clip_text_cache.get(text)
        if cached is not None:
            return cached
        import torch

        _setup_torch()
        model, processor = self.clip
        inputs = processor(text=[text], return_tensors="pt", padding=True, truncation=True)
        inputs = inputs.to(torch_device())
        with torch.inference_mode():
            features = model.get_text_features(**inputs)
        vector = _normalize(_to_numpy(features))[0]
        if len(self._clip_text_cache) >= config.EMBED_CACHE_SIZE:
            self._clip_text_cache.clear()
        self._clip_text_cache[text] = vector
        return vector

    def clip_image_embeddings(self, image_paths: list[str], batch_size: int = 16) -> np.ndarray:
        import torch
        from PIL import Image

        _setup_torch()
        model, processor = self.clip
        vectors: list[np.ndarray] = []
        for start in range(0, len(image_paths), batch_size):
            chunk = [
                Image.open(path).convert("RGB")
                for path in image_paths[start:start + batch_size]
            ]
            inputs = processor(images=chunk, return_tensors="pt")
            inputs = inputs.to(torch_device())
            with torch.inference_mode():
                features = model.get_image_features(**inputs)
            vectors.append(_to_numpy(features))
        if not vectors:
            return np.zeros((0, config.CLIP_DIM), dtype=np.float32)
        return _normalize(np.vstack(vectors))

    # ---- 文本向量 ----
    def text_embeddings(self, texts: list[str], batch_size: int = 32) -> np.ndarray:
        if not texts:
            return np.zeros((0, config.TEXT_DIM), dtype=np.float32)
        _setup_torch()
        pending: list[str] = []
        for text in texts:
            if text not in self._text_cache and text not in pending:
                pending.append(text)
        for start in range(0, len(pending), batch_size):
            chunk = pending[start:start + batch_size]
            try:
                # modelscope sentence-embedding pipeline 的入参需走 input= 关键字
                result = self.text(input={"source_sentence": chunk})
            except TypeError:
                result = self.text.forward({"source_sentence": chunk})
            array = _normalize(_to_numpy(result["text_embedding"]))
            for index, text in enumerate(chunk):
                if len(self._text_cache) >= config.EMBED_CACHE_SIZE:
                    self._text_cache.clear()
                self._text_cache[text] = array[index]
        try:
            return np.vstack([self._text_cache[text] for text in texts])
        except KeyError:
            # 缓存被并发清空时退化为全量编码
            return self.text_embeddings(list(texts), batch_size)

    # ---- ASR ----
    def transcribe(self, audio_path: str) -> list[dict]:
        try:
            result = self.asr.forward(audio_path, sentence_timestamp=True)
        except TypeError:
            result = self.asr(audio_path, sentence_timestamp=True)
        if isinstance(result, list):
            result = result[0] if result else {}
        if not isinstance(result, dict):
            return []

        segments = []
        sentences = result.get("sentences") or result.get("sentence_info")
        if sentences:
            for item in sentences:
                text = (item.get("text") or "").strip()
                if len(text) < config.SUBTITLE_MIN_CHARS:
                    continue
                segments.append(
                    {
                        "start_ms": int(item.get("start") or 0),
                        "end_ms": int(item.get("end") or 0),
                        "text": text,
                    }
                )
        if segments:
            return segments

        text = (result.get("text") or "").strip()
        if len(text) < config.SUBTITLE_MIN_CHARS:
            return []
        # 无标点/句级时间戳时，按字符时间戳切分为固定长度的字幕片段
        timestamps = result.get("timestamp") or []
        if len(timestamps) < len(text):
            return [{"start_ms": 0, "end_ms": 0, "text": text}]
        chunk = 20
        for start in range(0, len(text), chunk):
            piece = text[start:start + chunk].strip()
            if len(piece) < config.SUBTITLE_MIN_CHARS:
                continue
            segments.append(
                {
                    "start_ms": int(timestamps[start][0]),
                    "end_ms": int(timestamps[min(start + chunk, len(text)) - 1][1]),
                    "text": piece,
                }
            )
        return segments


registry = ModelRegistry()
