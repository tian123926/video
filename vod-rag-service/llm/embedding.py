"""Embedding 配置: Qwen3-Embedding-0.6B 本地加载(transformers), 封装为 LlamaIndex BaseEmbedding

参考 llama-index 官方 Custom Embeddings 写法:
BaseEmbedding 子类 + PrivateAttr 持有模型, 实现 _get_text_embedding / _get_query_embedding 接口,
即可直接传给 VectorStoreIndex / Settings.embed_model。
"""
from typing import Any, List

import numpy as np
import torch
from llama_index.core.bridge.pydantic import Field, PrivateAttr
from llama_index.core.embeddings import BaseEmbedding
from transformers import AutoModel, AutoTokenizer

from config import (
    EMBEDDING_BATCH_SIZE,
    EMBEDDING_MAX_LENGTH,
    EMBEDDING_MODEL_PATH,
    QUERY_INSTRUCTION,
)


class Qwen3Embedding(BaseEmbedding):
    """
    Qwen3-Embedding-0.6B (魔搭本地模型) 的 BaseEmbedding 封装。

    注意: Qwen3-Embedding 官方要求 last-token pooling(需 left padding),
    而不是 CLS([:, 0]) pooling, 此处按官方实现实现。
    """

    model_path: str = Field(default=EMBEDDING_MODEL_PATH, description="本地模型目录")
    max_length: int = Field(default=EMBEDDING_MAX_LENGTH, description="最大 token 长度")
    embed_dim: int = Field(default=1024, description="向量维度(加载后按模型实际配置覆盖)")
    query_instruction: str = Field(default=QUERY_INSTRUCTION, description="查询侧指令")

    _tokenizer: Any = PrivateAttr()
    _model: Any = PrivateAttr()
    _device: Any = PrivateAttr()

    def __init__(self, model_path: str = EMBEDDING_MODEL_PATH,
                 device: str | None = None,
                 batch_size: int = EMBEDDING_BATCH_SIZE,
                 max_length: int = EMBEDDING_MAX_LENGTH,
                 **kwargs):
        super().__init__(
            model_name=model_path,
            model_path=model_path,
            embed_batch_size=batch_size,
            max_length=max_length,
            **kwargs,
        )
        self._device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self._tokenizer = AutoTokenizer.from_pretrained(
            model_path, trust_remote_code=True, padding_side="left")
        self._model = AutoModel.from_pretrained(
            model_path, trust_remote_code=True).to(self._device).eval()
        self.embed_dim = self._model.config.hidden_size  # 0.6B -> 1024 维
        print(f"[Embed] Qwen3-Embedding 加载完成 device={self._device} dim={self.embed_dim}")

    @classmethod
    def class_name(cls) -> str:
        return "qwen3_embedding"

    @torch.no_grad()
    def _embed_batch(self, texts: List[str]) -> List[List[float]]:
        """last-token pooling + L2 归一化"""
        vecs = []
        for i in range(0, len(texts), self.embed_batch_size):
            batch = texts[i:i + self.embed_batch_size]
            encoded = self._tokenizer(
                batch, padding=True, truncation=True,
                max_length=self.max_length, return_tensors="pt",
            ).to(self._device)
            out = self._model(**encoded)
            vecs.append(out.last_hidden_state[:, -1, :].float().cpu().numpy())
        emb = np.concatenate(vecs)
        emb = emb / np.linalg.norm(emb, axis=1, keepdims=True)
        return emb.tolist()

    def _get_text_embedding(self, text: str) -> List[float]:
        return self._embed_batch([text])[0]

    def _get_text_embeddings(self, texts: List[str]) -> List[List[float]]:
        return self._embed_batch(texts)

    def _get_query_embedding(self, query: str) -> List[float]:
        # 官方推荐查询侧拼接指令: Instruct: {instruction}\nQuery: {query}
        text = f"Instruct: {self.query_instruction}\nQuery: {query}"
        return self._embed_batch([text])[0]

    async def _aget_query_embedding(self, query: str) -> List[float]:
        return self._get_query_embedding(query)

    async def _aget_text_embedding(self, text: str) -> List[float]:
        return self._get_text_embedding(text)
