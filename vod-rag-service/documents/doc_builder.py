"""MinerU content_list.json -> 多模态 Document (图片/表格/公式/文字)"""
import json
from pathlib import Path

from llama_index.core import Document
from llama_index.core.schema import ImageDocument


def build_multimodal_documents(content_list_path: str, base_dir: str) -> list[Document]:
    """
    方案二: 从 MinerU 的 content_list.json 构造多模态 Document
      - text/title/list/code -> Document(text=...)
      - table               -> Document(text=表格HTML+caption, metadata type=table)
      - equation            -> Document(text=LaTeX, metadata type=equation)
      - image/chart         -> ImageDocument(image_path=...), 附带 caption 文本
    base_dir 为 content_list.json 所在目录(用于解析 images/ 相对路径)。
    """
    base_dir = Path(base_dir)
    data = json.loads(Path(content_list_path).read_text(encoding="utf-8"))
    docs: list[Document] = []

    for idx, item in enumerate(data):
        t = item.get("type", "text")
        page = item.get("page_idx")
        meta = {"type": t, "page": page, "seq": idx, "source": content_list_path}

        if t in ("text", "title", "list", "code"):
            if text := item.get("text", ""):
                docs.append(Document(text=text, metadata=meta))

        elif t == "table":
            parts = list(item.get("table_caption") or []) \
                + ([item["table_body"]] if item.get("table_body") else []) \
                + list(item.get("table_footnote") or [])
            if parts:
                docs.append(Document(text="\n".join(parts), metadata=meta))

        elif t == "equation":
            if text := item.get("text", ""):  # LaTeX, 通常含 $$...$$
                docs.append(Document(text=text, metadata=meta))

        elif t in ("image", "chart"):
            if img_rel := item.get("img_path"):
                img_abs = (base_dir / img_rel).resolve()
                if img_abs.exists():
                    caption = (item.get("image_caption") or item.get("chart_caption") or [])
                    docs.append(ImageDocument(
                        image_path=str(img_abs),
                        text=caption[0] if caption else "",
                        metadata=meta,
                    ))
    return docs
