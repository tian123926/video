"""HierarchicalNodeParser 层级切片 (表格/图片/公式等结构化内容不切片)"""
from llama_index.core import Document
from llama_index.core.node_parser import HierarchicalNodeParser
from llama_index.core.schema import ImageDocument, ImageNode, TextNode

from config import CHUNK_SIZES

# 不参与切片的类型: 表格 / 图片 / 图表 / 公式(整体保留)
STRUCT_TYPES = {"table", "image", "chart", "equation"}


def build_hierarchical_nodes(
    documents: list[Document],
    chunk_sizes: list[int] | None = None,
) -> tuple[list, list, list]:
    """
    用 HierarchicalNodeParser 对文本类 Document 做层级切片(root -> parent -> leaf,
    通过 index_id 维护父子关系); 表格/图片/图表/公式 等结构化内容整体保留为单个
    leaf node(不切片), 避免把一个表格/公式切成两半破坏语义。

    返回 (all_nodes, hier_nodes, struct_nodes):
      - all_nodes:   全部节点(层级 + 结构化), 用于写入 docstore 与索引
      - hier_nodes:  文本类层级切片结果(可继续用 get_leaf_nodes/get_root_nodes)
      - struct_nodes:结构化整体节点(table/image/chart/equation, 未切片)
    """
    if chunk_sizes is None:
        chunk_sizes = CHUNK_SIZES

    text_docs = [d for d in documents if d.metadata.get("type") not in STRUCT_TYPES]
    struct_docs = [d for d in documents if d.metadata.get("type") in STRUCT_TYPES]

    # 1) 文本类: 层级切片
    parser = HierarchicalNodeParser.from_defaults(chunk_sizes=chunk_sizes)
    hier_nodes = parser.get_nodes_from_documents(text_docs, show_progress=False)

    # 2) 结构化类: 整体作为 leaf node, 不切片
    struct_nodes = []
    for d in struct_docs:
        meta = {**d.metadata, "splittable": False}
        if isinstance(d, ImageDocument):
            node = ImageNode(
                text=d.get_content(),
                image_path=getattr(d, "image_path", None),
                metadata=meta,
            )
        else:
            node = TextNode(text=d.get_content(), metadata=meta)
        struct_nodes.append(node)

    all_nodes = hier_nodes + struct_nodes
    return all_nodes, hier_nodes, struct_nodes
