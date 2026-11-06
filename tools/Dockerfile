FROM python:3.11-slim-bookworm

ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ENV DEBIAN_FRONTEND=noninteractive
ENV TOOLS_WORK_ROOT=/vod-transcode
ENV TOOLS_PORT=8090

RUN sed -i 's#http://deb.debian.org#https://mirrors.tuna.tsinghua.edu.cn#g' \
      /etc/apt/sources.list.d/debian.sources \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      ffmpeg \
 && rm -rf /var/lib/apt/lists/* \
 && pip install --no-cache-dir -i "${PIP_INDEX_URL}" yt-dlp \
 && yt-dlp --version \
 && ffmpeg -version | head -n 1

COPY download-bilibili.sh /usr/local/bin/download-bilibili
COPY server.py /opt/vod-tools/server.py
RUN chmod +x /usr/local/bin/download-bilibili /opt/vod-tools/server.py

WORKDIR /vod-transcode
EXPOSE 8090

ENTRYPOINT ["python3", "/opt/vod-tools/server.py"]
