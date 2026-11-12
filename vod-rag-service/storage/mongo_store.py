"""MongoDB docstore: 持久化全部层级节点(root/parent/leaf + 结构化节点)

摄入阶段把所有节点(含父节点、祖节点)写入 MongoDB;
检索阶段通过 RRF 命中的子节点 id 经 docstore.get_node 获取父节点与祖节点, 供 AutoMergingRetriever 合并。
"""
from llama_index.core import StorageContext
from llama_index.storage.docstore.mongodb import MongoDocumentStore

from config import MONGO_DB, MONGO_DOCSTORE_COLLECTION, MONGO_URI


def build_mongo_docstore(uri: str = MONGO_URI,
                         db_name: str = MONGO_DB,
                         namespace: str = MONGO_DOCSTORE_COLLECTION) -> MongoDocumentStore:
    """连接 MongoDB docstore (db_name 库下的 namespace collection)"""
    store = MongoDocumentStore.from_uri(uri=uri, db_name=db_name, namespace=namespace)
    print(f"[MongoDB] docstore 已连接 {uri} db={db_name} collection={namespace}")
    return store


def build_storage_context(docstore: MongoDocumentStore) -> StorageContext:
    """用 MongoDB docstore 构造 StorageContext (AutoMergingRetriever 从中读父/祖节点)"""
    return StorageContext.from_defaults(docstore=docstore)
