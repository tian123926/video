"""渗透测试知识库直灌脚本: markdown -> 层级切片 -> MongoDB docstore + Milvus + ES BM25

用法(在 rag-api 容器内执行, 依赖镜像内依赖与容器网络):
    docker exec rag-api python scripts/ingest_knowledge.py
    docker exec rag-api python scripts/ingest_knowledge.py --reset   # 重建 Milvus collection

知识源: documents/pentest_knowledge/*.md
按 "## " 标题切分为多个 Document(标题入 metadata), 走与 PDF 摄入一致的层级切片链路。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# 脚本直跑时把项目根加入 sys.path(镜像内 /app)
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from llama_index.core import Document, StorageContext, VectorStoreIndex
from llama_index.core.node_parser import get_leaf_nodes

from documents import build_hierarchical_nodes
from llm import Qwen3Embedding
from retrieval import build_es_keyword_index
from storage import build_mongo_docstore
from vectordb import build_vector_store

KNOWLEDGE_DIR = Path("documents/pentest_knowledge")


def parse_markdown(md_path: Path) -> list[Document]:
    """按 "## " 小节切分 markdown; "## 标题" 一并作为 Document 文本头部。

    metadata 与 MinerU content_list 链路对齐:
      type: text | title, page: None, source: 文件名, seq: 小节序号
    """
    raw = md_path.read_text(encoding="utf-8")
    file_title = ""
    m = re.match(r"\s*#\s+(.+)", raw)
    if m:
        file_title = m.group(1).strip()

    sections: list[str] = []
    cur: list[str] = []
    for line in raw.splitlines():
        if line.startswith("## ") and cur:
            sections.append("\n".join(cur).strip())
            cur = [line]
        else:
            cur.append(line)
    if cur:
        sections.append("\n".join(cur).strip())

    docs: list[Document] = []
    for seq, sec in enumerate(sections):
        if not sec:
            continue
        is_title_only = sec.count("\n") == 0 and sec.startswith("# ")
        docs.append(Document(
            text=sec,
            metadata={
                "type": "title" if is_title_only else "text",
                "page": None,
                "seq": seq,
                "source": md_path.name,
                "doc_title": file_title,
                "section": sec.splitlines()[0].lstrip("# ").strip() if sec.startswith("#") else "",
            },
        ))
    return docs


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reset", action="store_true", help="重建 Milvus collection(清空向量)")
    ap.add_argument("--dir", default=str(KNOWLEDGE_DIR), help="知识目录")
    args = ap.parse_args()

    files = sorted(Path(args.dir).glob("*.md"))
    if not files:
        print(f"[ingest] 未找到知识文件: {args.dir}/*.md")
        return 1

    documents: list[Document] = []
    for f in files:
        sec_docs = parse_markdown(f)
        documents.extend(sec_docs)
        print(f"[ingest] {f.name}: {len(sec_docs)} 小节")

    print(f"[ingest] 共 {len(documents)} 个 Document, 开始层级切片")
    all_nodes, hier_nodes, struct_nodes = build_hierarchical_nodes(documents)
    leaf_nodes = get_leaf_nodes(hier_nodes) + struct_nodes
    print(f"[ingest] 切片完成: 全部节点 {len(all_nodes)}, leaf {len(leaf_nodes)} "
          f"(hier {len(hier_nodes)} + struct {len(struct_nodes)})")

    # 1) 全部节点(含父/祖) -> MongoDB docstore (检索期 AutoMerge 取父/祖)
    docstore = build_mongo_docstore()
    docstore.add_documents(all_nodes)

    # 2) leaf -> Milvus (向量) + ES (BM25)
    embedder = Qwen3Embedding()
    vector_store = build_vector_store(dim=embedder.embed_dim, overwrite=args.reset)
    storage_context = StorageContext.from_defaults(vector_store=vector_store)
    VectorStoreIndex(leaf_nodes, storage_context=storage_context,
                     embed_model=embedder, show_progress=True)
    build_es_keyword_index(leaf_nodes, embedder)

    print(f"[ingest] 完成: Milvus collection={args.reset}重建/增量, "
          f"leaf 入库 {len(leaf_nodes)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

