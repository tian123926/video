"""
多模态 RAG 数据入库 + 混合检索主流程:
  1. MinerU 本地解析 PDF -> markdown + content_list.json + images/
  2. content_list.json -> 多模态 Document (图片/表格/公式/文字)
  3. HierarchicalNodeParser 层级切片 (表格/图片/公式不切片)
  4. Qwen3-Embedding(BaseEmbedding) + LlamaIndex 官方 Milvus 连接器
     -> VectorStoreIndex 向量化 leaf 节点, 存入索引与向量库(密集检索)
  5. ES 连接器 BM25 关键词检索 (只入 all_leaf, 不含父节点)
  6. 检索链路: RRF 融合 -> AutoMerge 父子合并 -> Rerank 精排 -> 查询引擎
  7. 检索演示

运行: python main.py
依赖: pip install llama-index llama-index-vector-stores-milvus pymilvus \
      llama-index-vector-stores-elasticsearch sentence-transformers transformers torch
"""
from pathlib import Path

from llama_index.core import StorageContext, VectorStoreIndex
from llama_index.core.node_parser import get_leaf_nodes, get_root_nodes
from llama_index.core.schema import ImageDocument

from config import ES_INDEX, PDF_PATH, QUERY_LLM, RETRIEVAL_TOP_K
from documents import build_hierarchical_nodes, build_multimodal_documents
from llm import Qwen3Embedding
from parsing import parse_pdf_with_mineru
from retrieval import (
    build_es_keyword_index,
    build_query_engine,
    load_es_keyword_retriever,
)
from vectordb import build_vector_store


def main():
    # 1. MinerU 解析 PDF -> markdown + content_list.json + images/
    md_path = parse_pdf_with_mineru(PDF_PATH)
    print(f"[OK] markdown: {md_path}")

    # 2. content_list.json -> 多模态 Document (图片/表格/公式/文字)
    content_list = Path(md_path).with_name(f"{Path(PDF_PATH).stem}_content_list.json")
    if not content_list.exists():
        raise FileNotFoundError(f"未找到 {content_list}, 请确认 MinerU 输出")
    docs = build_multimodal_documents(str(content_list), base_dir=content_list.parent)
    n_img = sum(1 for d in docs if isinstance(d, ImageDocument))
    print(f"[Document] 共 {len(docs)} 个 Document (图片 {n_img} 张)")

    # 3. HierarchicalNodeParser 层级切片 (表格/图片/公式等不切片, 整体保留)
    all_nodes, hier_nodes, struct_nodes = build_hierarchical_nodes(docs)
    leaf_nodes = get_leaf_nodes(hier_nodes)
    root_nodes = get_root_nodes(hier_nodes)
    print(f"[切片] 文本层级节点 {len(hier_nodes)} "
          f"(root {len(root_nodes)} / leaf {len(leaf_nodes)})")
    print(f"[切片] 结构化整体节点 {len(struct_nodes)} (不切片: table/image/chart/equation)")
    print(f"[切片] 总节点 {len(all_nodes)}")

    all_leaf = leaf_nodes + struct_nodes  # 向量库与 ES 都只入 leaf, 不含父节点

    # 4. Qwen3-Embedding + 官方 Milvus 连接器 -> 密集向量索引
    embedder = Qwen3Embedding()
    vector_store = build_vector_store(dim=embedder.embed_dim)
    storage_context = StorageContext.from_defaults(vector_store=vector_store)
    # 全部节点(含 parent/root 层)写入 docstore, 保留层级关系, 供 AutoMergingRetriever 使用
    storage_context.docstore.add_documents(all_nodes)

    index = VectorStoreIndex(
        all_leaf,
        storage_context=storage_context,
        embed_model=embedder,
        show_progress=True,
    )
    print(f"[Milvus] 已向量化并入库 {len(all_leaf)} 个 leaf 节点 -> 索引+向量库")

    # 5. ES BM25 关键词检索 (只入 all_leaf, 不含父节点)
    build_es_keyword_index(all_leaf, embedder)
    es_retriever = load_es_keyword_retriever(embedder)
    print(f"[ES] 已写入 {len(all_leaf)} 个 leaf 节点 -> {ES_INDEX}")

    # 6. 检索链路: RRF 融合 -> AutoMerge 父子合并 -> Rerank 精排 -> 查询引擎
    query_engine, auto_merge_retriever, reranker = build_query_engine(
        index, es_retriever, storage_context,
    )
    print("[链路] RRF(密集+BM25) -> AutoMerge -> Rerank -> QueryEngine 组装完成")

    # 7. 检索演示 (不依赖 LLM: RRF -> AutoMerge -> Rerank 逐步执行)
    query = "示例问题, 替换为你的实际问题"
    nodes = auto_merge_retriever.retrieve(query)          # RRF 融合 + 父子合并
    nodes = reranker.postprocess_nodes(nodes, query)      # 精排
    print(f"[检索] '{query}' 精排后 top-{len(nodes)}:")
    for r in nodes:
        m = r.node.metadata
        print(f"  score={r.score:.4f} type={m.get('type')} page={m.get('page')} "
              f"text={r.get_content()[:100]}")

    # 完整问答: 把 config.QUERY_LLM 配置为真实 LLM 实例后调用
    # response = query_engine.query(query)
    print(f"[提示] query_engine 已就绪 (QUERY_LLM={'已配置' if QUERY_LLM else 'MockLLM'})")


if __name__ == "__main__":
    main()
