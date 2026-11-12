# 多模态RAG API 镜像 (CPU 版; GPU 版见文件末尾注释)
# 摄入/检索两个服务共用本镜像, 启动命令由 docker-compose.yml 指定
FROM python:3.12-slim

# pip 全局清华源 (根治后续所有 pip install 超时)
RUN pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple && \
    pip config set global.trusted-host pypi.tuna.tsinghua.edu.cn

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    HF_HOME=/models/hf \
    MODELSCOPE_CACHE=/models/modelscope \
    TZ=Asia/Shanghai

WORKDIR /app

# 换 apt 国内源 (阿里云)
RUN sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources 2>/dev/null \
 || sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list

# 系统依赖: opencv(libgl/glib)、OpenMP(paddle)、中文字体(OCR渲染)、curl(健康检查)
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 \
        libglib2.0-0 \
        libgomp1 \
        fonts-noto-cjk \
        curl \
    && rm -rf /var/lib/apt/lists/*

# torch CPU 版单独成层 (避免拉取 CUDA 大包, 镜像从 ~8GB 降到 ~3GB)
RUN pip install torch --index-url https://download.pytorch.org/whl/cpu --timeout 300

# Python 依赖 (requirements 变更少时此层走缓存)
COPY requirements.txt .
RUN pip install -r requirements.txt

# 项目代码
COPY . .

EXPOSE 8000

# 统一入口: 摄入 + 检索 合并到一个进程 (见 api/app.py)
CMD ["uvicorn", "api.app:app", "--host", "0.0.0.0", "--port", "8000"]

# ================= GPU 版改造说明 =================
# 1) 基础镜像换: nvidia/cuda:12.4.1-cudnn-runtime-ubuntu22.04 (需先装 python3.12 或改用 pytorch/pytorch 官方镜像)
# 2) torch 装默认 CUDA 版: RUN pip install torch  (删掉 --index-url 那行)
# 3) docker-compose.yml 的 ingest-api/retrieve-api 加:
#      deploy:
#        resources:
#          reservations:
#            devices:
#              - driver: nvidia
#                count: all
#                capabilities: [gpu]
# 4) 宿主机需装 NVIDIA Container Toolkit; MINERU_BACKEND 可设为 vlm-engine
