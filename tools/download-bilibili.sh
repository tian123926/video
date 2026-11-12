#!/usr/bin/env bash
#
# 把 B 站视频转成 HLS（m3u8 + ts）并上传到 MinIO。
#
# 优先尝试 yt-dlp 直接解析 B 站原生 HLS 地址（m3u8），直接搬运 ts；
# 如果该视频只有 DASH 流（B 站绝大多数视频都是这种情况），则退回
# yt-dlp 下载音视频后用 ffmpeg 切成 HLS。最终产物始终是 m3u8 + ts，
# 不会把一个合并后的 mp4 留在 MinIO。
#
# 用法示例（本机）：
#   docker run --rm --entrypoint /usr/local/bin/download-bilibili vod-tools:local \
#     --url "https://www.bilibili.com/video/BV1xx411c7mD" \
#     --video-id BV1xx411c7mD \
#     --minio-base http://host.docker.internal:8081
#
set -euo pipefail

usage() {
    cat <<'EOF'
用法:
  download-bilibili --url <B站视频地址> [options]

必填:
  -u, --url <url>              B 站视频地址
  -i, --video-id <id>          对象前缀里使用的视频 ID（不传则取 yt-dlp 解析出的 ID）
  -T, --title <title>          视频标题，写入数据库（默认使用 video-id）
  -D, --description <text>     视频简介，写入数据库
  -L, --duration-ms <ms>       视频时长（毫秒），不传则自动解析

可选:
  -b, --bucket <name>          HLS 目标桶，默认 video-hls
  -p, --prefix <prefix>        对象前缀，默认 hls/<video-id>/
  -f, --format <selector>      优先尝试的 yt-dlp 格式，默认 b*[protocol^=m3u8]
  -c, --cookies <file>         Netscape 格式 cookies 文件（高清/会员视频需要）
  -w, --work-dir <dir>         工作目录，默认使用临时目录
  -m, --minio-base <url>       minio 微服务地址
                               环境变量 MINIO_BASE_URL，默认 http://127.0.0.1:8081
  -t, --token <token>          内部服务令牌
                               环境变量 VOD_SERVICE_TOKEN，默认 vod-internal-token
  -k, --keep                   保留工作目录（排查问题用）
  -h, --help                   显示帮助

流程:
  1. 尝试 yt-dlp -g 拿到单个 m3u8 地址，原生搬运 ts
  2. 拿不到 m3u8（只有 DASH）时，yt-dlp 下载并 ffmpeg 生成 HLS
     （先 -c copy，失败则重编码 H.264/AAC）
  3. 先上传所有 ts，最后上传 index.m3u8
EOF
}

VIDEO_URL=""
VIDEO_ID=""
TITLE=""
DESCRIPTION=""
DURATION_MS=""
BUCKET="video-hls"
PREFIX=""
FORMAT="${YTDLP_FORMAT:-b*[protocol^=m3u8]}"
COOKIES=""
WORK_DIR=""
KEEP=0
MINIO_BASE_URL="${MINIO_BASE_URL:-http://127.0.0.1:8081}"
VOD_SERVICE_TOKEN="${VOD_SERVICE_TOKEN:-vod-internal-token}"

REFERER="https://www.bilibili.com/"
USER_AGENT="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

while [[ $# -gt 0 ]]; do
    case "$1" in
        -u|--url) VIDEO_URL="$2"; shift 2 ;;
        -i|--video-id) VIDEO_ID="$2"; shift 2 ;;
        -T|--title) TITLE="$2"; shift 2 ;;
        -D|--description) DESCRIPTION="$2"; shift 2 ;;
        -L|--duration-ms) DURATION_MS="$2"; shift 2 ;;
        -b|--bucket) BUCKET="$2"; shift 2 ;;
        -p|--prefix) PREFIX="$2"; shift 2 ;;
        -f|--format) FORMAT="$2"; shift 2 ;;
        -c|--cookies) COOKIES="$2"; shift 2 ;;
        -w|--work-dir) WORK_DIR="$2"; shift 2 ;;
        -m|--minio-base) MINIO_BASE_URL="$2"; shift 2 ;;
        -t|--token) VOD_SERVICE_TOKEN="$2"; shift 2 ;;
        -k|--keep) KEEP=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "未知参数: $1" >&2; usage; exit 1 ;;
    esac
done

if [[ -z "$VIDEO_URL" ]]; then
    echo "缺少 --url" >&2
    usage
    exit 1
fi

for cmd in yt-dlp curl ffmpeg ffprobe; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "镜像缺少命令: $cmd" >&2; exit 1; }
done

MINIO_BASE_URL="${MINIO_BASE_URL%/}"

if [[ -z "$VIDEO_ID" ]]; then
    echo "[1/4] 解析视频 ID"
    VIDEO_ID="$(yt-dlp --no-warnings --skip-download --print id "$VIDEO_URL" | head -n 1)"
fi
VIDEO_ID="${VIDEO_ID//[^A-Za-z0-9._-]/_}"

if [[ -z "$PREFIX" ]]; then
    PREFIX="hls/${VIDEO_ID}/"
fi

if [[ -z "$WORK_DIR" ]]; then
    WORK_DIR="$(mktemp -d /tmp/vod-bilibili.XXXXXX)"
else
    rm -rf "$WORK_DIR"
    mkdir -p "$WORK_DIR"
fi

cleanup() {
    if [[ "$KEEP" -eq 0 ]]; then
        rm -rf "$WORK_DIR"
    else
        echo "工作目录保留在: $WORK_DIR"
    fi
}
trap cleanup EXIT

HLS_DIR="$WORK_DIR/hls"
mkdir -p "$HLS_DIR"
SOURCE_FILE=""

yt_dlp_common=(--no-playlist --no-warnings)
if [[ -n "$COOKIES" ]]; then
    yt_dlp_common=(--cookies "$COOKIES" "${yt_dlp_common[@]}")
fi

if [[ -z "$TITLE" ]]; then
    TITLE="$VIDEO_ID"
fi

if [[ -z "$DURATION_MS" ]]; then
    duration_seconds="$(yt-dlp "${yt_dlp_common[@]}" --skip-download --print duration "$VIDEO_URL" 2>/dev/null | head -n 1 || true)"
    if [[ "$duration_seconds" =~ ^[0-9]+$ ]]; then
        DURATION_MS=$((duration_seconds * 1000))
    fi
fi

curl_headers=(-H "Referer: $REFERER" -H "User-Agent: $USER_AGENT")
curl_cookies=()
if [[ -n "$COOKIES" ]]; then
    curl_cookies=(-b "$COOKIES")
fi

native_hls=false
echo "[2/4] 尝试解析 B 站原生 HLS 地址"
stream_urls="$(yt-dlp "${yt_dlp_common[@]}" -f "$FORMAT" -g "$VIDEO_URL" 2>/dev/null || true)"
url_count="$(printf '%s\n' "$stream_urls" | grep -c . || true)"
if [[ "$url_count" -eq 1 && "$stream_urls" == *".m3u8"* ]]; then
    native_hls=true
fi

if [[ "$native_hls" == true ]]; then
    echo "找到原生 m3u8，直接搬运 ts 分片"
    REMOTE_PLAYLIST="$HLS_DIR/remote.m3u8"
    curl -fsSL "${curl_headers[@]}" "${curl_cookies[@]}" "$stream_urls" -o "$REMOTE_PLAYLIST"

    if ! grep -q '^#EXTM3U' "$REMOTE_PLAYLIST" \
        || grep -q '^#EXT-X-MAP' "$REMOTE_PLAYLIST" \
        || grep -q '^#EXT-X-KEY' "$REMOTE_PLAYLIST"; then
        echo "原生播放列表不是可直接搬运的 ts 结构，改为下载后切片"
        native_hls=false
        rm -f "$REMOTE_PLAYLIST"
    fi
fi

if [[ "$native_hls" == true ]]; then
    LOCAL_PLAYLIST="$HLS_DIR/index.m3u8"
    : > "$LOCAL_PLAYLIST"
    segment_count=0

    append_line() {
        printf '%s\n' "$1" >> "$LOCAL_PLAYLIST"
    }

    while IFS= read -r raw_line; do
        line="${raw_line%$'\r'}"
        trimmed="${line#"${line%%[![:space:]]*}"}"
        trimmed="${trimmed%"${trimmed##*[![:space:]]}"}"
        [[ -z "$trimmed" ]] && continue

        if [[ "$trimmed" == \#* ]]; then
            append_line "$trimmed"
            continue
        fi

        segment_url="$trimmed"
        if [[ "$segment_url" != http* ]]; then
            base_dir="${stream_urls%%\?*}"
            base_dir="${base_dir%/*}/"
            segment_url="$base_dir$segment_url"
        fi

        segment_name="$(printf 'seg-%05d.ts' "$segment_count")"
        curl -fsSL "${curl_headers[@]}" "${curl_cookies[@]}" "$segment_url" \
            -o "$HLS_DIR/$segment_name"
        append_line "$segment_name"
        segment_count=$((segment_count + 1))
    done < "$REMOTE_PLAYLIST"

    if [[ "$segment_count" -eq 0 ]]; then
        echo "原生播放列表没有可下载分片" >&2
        exit 1
    fi
else
    echo "[3/4] 未找到可搬运的 m3u8，下载音视频并切片"
    yt-dlp "${yt_dlp_common[@]}" \
        --restrict-filenames \
        --merge-output-format mp4 \
        -f "bv*+ba/b" \
        -o "$WORK_DIR/source.%(ext)s" \
        "$VIDEO_URL"

    SOURCE_FILE="$(find "$WORK_DIR" -maxdepth 1 -type f -name 'source.*' ! -name '*.part' | head -n 1)"
    if [[ -z "$SOURCE_FILE" ]]; then
        echo "未找到下载后的视频文件" >&2
        exit 1
    fi

    hls_copy() {
        ffmpeg -y -hide_banner -loglevel error -i "$SOURCE_FILE" \
            -c copy \
            -f hls \
            -hls_time 6 \
            -hls_list_size 0 \
            -hls_playlist_type vod \
            -hls_segment_filename "$HLS_DIR/seg-%05d.ts" \
            "$HLS_DIR/index.m3u8"
    }

    hls_reencode() {
        ffmpeg -y -hide_banner -loglevel error -i "$SOURCE_FILE" \
            -c:v libx264 -preset ultrafast -crf 25 -threads 2 \
            -c:a aac -b:a 128k \
            -f hls \
            -hls_time 6 \
            -hls_list_size 0 \
            -hls_playlist_type vod \
            -hls_segment_filename "$HLS_DIR/seg-%05d.ts" \
            "$HLS_DIR/index.m3u8"
    }
    if ! hls_copy; then
        echo "copy 模式失败，改用 H.264/AAC 重编码"
        rm -f "$HLS_DIR"/seg-*.ts "$HLS_DIR/index.m3u8"
        hls_reencode
    fi
    LOCAL_PLAYLIST="$HLS_DIR/index.m3u8"
fi

video_codec="$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name -of default=nw=1:nk=1 \
    "$HLS_DIR/seg-00000.ts" 2>/dev/null | head -n 1 || true)"
if [[ "$video_codec" != "h264" ]]; then
    echo "视频编码为 ${video_codec:-未知}，转码为 H.264/AAC 以保证客户端有画面"
    REENCODE_DIR="$WORK_DIR/reencode"
    rm -rf "$REENCODE_DIR"
    mkdir -p "$REENCODE_DIR"
    if [[ -n "$SOURCE_FILE" && -f "$SOURCE_FILE" ]]; then
        ffmpeg -y -hide_banner -loglevel error -i "$SOURCE_FILE" \
            -c:v libx264 -preset ultrafast -crf 25 -threads 2 \
            -c:a aac -b:a 128k \
            -f hls \
            -hls_time 6 \
            -hls_list_size 0 \
            -hls_playlist_type vod \
            -hls_segment_filename "$REENCODE_DIR/seg-%05d.ts" \
            "$REENCODE_DIR/index.m3u8"
    else
        CONCAT_LIST="$WORK_DIR/concat.txt"
        : > "$CONCAT_LIST"
        find "$HLS_DIR" -maxdepth 1 -type f -name 'seg-*.ts' | sort | while IFS= read -r seg; do
            printf "file '%s'\n" "$seg" >> "$CONCAT_LIST"
        done
        ffmpeg -y -hide_banner -loglevel error -f concat -safe 0 -i "$CONCAT_LIST" \
            -c:v libx264 -preset ultrafast -crf 25 -threads 2 \
            -c:a aac -b:a 128k \
            -f hls \
            -hls_time 6 \
            -hls_list_size 0 \
            -hls_playlist_type vod \
            -hls_segment_filename "$REENCODE_DIR/seg-%05d.ts" \
            "$REENCODE_DIR/index.m3u8"
    fi
    rm -f "$HLS_DIR"/seg-*.ts "$HLS_DIR/index.m3u8"
    mv "$REENCODE_DIR"/* "$HLS_DIR"/
    rmdir "$REENCODE_DIR"
    LOCAL_PLAYLIST="$HLS_DIR/index.m3u8"
fi

segment_count="$(find "$HLS_DIR" -maxdepth 1 -type f -name 'seg-*.ts' | wc -l | tr -d ' ')"
if [[ "$segment_count" -eq 0 ]]; then
    echo "未生成任何 ts 分片" >&2
    exit 1
fi

upload_object() {
    local bucket="$1"
    local object_name="$2"
    local file="$3"
    local response
    response="$(curl -sS -f -X POST "$MINIO_BASE_URL/internal/upload" \
        -H "X-Service-Token: $VOD_SERVICE_TOKEN" \
        -F "bucket=$bucket" \
        -F "objectName=$object_name" \
        -F "file=@$file")"
    if ! grep -q '"code"[[:space:]]*:[[:space:]]*0' <<<"$response"; then
        echo "上传失败: $object_name -> $response" >&2
        exit 1
    fi
}

echo "[4/5] 上传到 MinIO: $MINIO_BASE_URL bucket=$BUCKET prefix=$PREFIX ($segment_count 个分片)"
for ts_file in "$HLS_DIR"/seg-*.ts; do
    upload_object "$BUCKET" "${PREFIX}$(basename "$ts_file")" "$ts_file"
done
upload_object "$BUCKET" "${PREFIX}index.m3u8" "$LOCAL_PLAYLIST"

COVER_OBJECT=""
COVER_FILE="$WORK_DIR/cover.jpg"
cover_source="${SOURCE_FILE:-}"
if [[ -z "$cover_source" ]]; then
    cover_source="$(find "$HLS_DIR" -maxdepth 1 -type f -name 'seg-*.ts' | sort | head -n 1)"
fi
if [[ -n "$cover_source" ]] && ffmpeg -y -hide_banner -loglevel error \
    -i "$cover_source" -frames:v 1 -q:v 2 "$COVER_FILE" >/dev/null 2>&1 \
    && [[ -s "$COVER_FILE" ]]; then
    COVER_OBJECT="cover/${VIDEO_ID}.jpg"
    upload_object "video-cover" "$COVER_OBJECT" "$COVER_FILE"
fi

echo "[5/5] 写入 MySQL 视频记录 videoKey=$VIDEO_ID"
register_response="$(curl -sS -f -X POST "$MINIO_BASE_URL/internal/video/register" \
    -H "X-Service-Token: $VOD_SERVICE_TOKEN" \
    --data-urlencode "videoKey=$VIDEO_ID" \
    --data-urlencode "title=$TITLE" \
    --data-urlencode "description=$DESCRIPTION" \
    --data-urlencode "coverObject=$COVER_OBJECT" \
    --data-urlencode "hlsObject=${PREFIX}index.m3u8" \
    --data-urlencode "durationMs=${DURATION_MS:-0}")"
if ! grep -q '"code"[[:space:]]*:[[:space:]]*0' <<<"$register_response"; then
    echo "写入数据库失败: $register_response" >&2
    exit 1
fi

echo "完成: videoId=$VIDEO_ID，共上传 $segment_count 个 ts 分片"
echo "数据库响应: $register_response"
echo "播放列表对象: $BUCKET/$PREFIX index.m3u8"
