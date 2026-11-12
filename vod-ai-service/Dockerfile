FROM python:3.10-slim

ENV PIP_INDEX_URL=http://mirrors.aliyun.com/pypi/simple/ \
    PIP_TRUSTED_HOST=mirrors.aliyun.com \
    PYTHONUNBUFFERED=1 \
    VOD_AI_DEVICE=cpu \
    MODEL_ROOT=/app/models

RUN sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg libgl1 libglib2.0-0 libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 使用预下载的 CPU 版 wheel（国内镜像站限速时避免反复慢速下载）
COPY wheels /tmp/wheels
RUN --mount=type=cache,target=/root/.cache/pip pip install --timeout 180 --retries 20 \
    --find-links=/tmp/wheels torch==2.3.1+cpu torchaudio==2.3.1+cpu torchvision==0.18.1+cpu \
    && rm -rf /tmp/wheels

COPY requirements.txt .
RUN --mount=type=cache,target=/root/.cache/pip pip install --timeout 180 --retries 20 -r requirements.txt

COPY app ./app
COPY models ./models
# ASR 管线依赖的 VAD / 标点模型（预先缓存，避免容器启动后重复下载 1GB+）
COPY models-cache /app/models-cache

ENV MODELSCOPE_CACHE=/app/models-cache \
    MALLOC_TRIM_THRESHOLD_=1048576

EXPOSE 8095

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8095", "--workers", "1"]
