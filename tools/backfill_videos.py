#!/usr/bin/env python3
"""
给已经上传到 MinIO、但还没有写 MySQL 的 B 站视频补登记。

流程：
  1. 列出 k8s MinIO 里 video-hls/hls/ 下的视频前缀
  2. 调 B 站 view 接口拿标题、时长、封面地址
  3. 下载封面并上传到 video-cover
  4. 调 /internal/video/register 幂等写入 t_video
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
API_HEADERS = {
    "User-Agent": USER_AGENT,
    "Referer": "https://www.bilibili.com/",
    "Accept": "application/json",
}


def fetch_json(url, timeout=15):
    request = urllib.request.Request(url, headers=API_HEADERS)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("code") != 0:
        return None
    return payload.get("data") or None


def list_object_prefixes(namespace):
    command = [
        "kubectl", "-n", namespace, "exec", "deploy/minio", "--",
        "sh", "-c", "ls -1 /data/video-hls/hls",
    ]
    result = subprocess.run(command, capture_output=True, text=True, check=True)
    prefixes = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    return [prefix for prefix in prefixes if prefix.startswith("BV")]


def build_multipart(fields, file_field, file_path, filename, content_type):
    boundary = "----vod" + uuid.uuid4().hex
    chunks = []
    for name, value in fields.items():
        chunks.append(
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
            f"{value}\r\n".encode("utf-8")
        )
    chunks.append(
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n".encode("utf-8")
    )
    with open(file_path, "rb") as handle:
        chunks.append(handle.read())
    chunks.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))
    return boundary, b"".join(chunks)


def upload_object(base_url, token, bucket, object_name, file_path, content_type):
    boundary, body = build_multipart(
        {"bucket": bucket, "objectName": object_name},
        "file", file_path, os.path.basename(file_path), content_type,
    )
    request = urllib.request.Request(
        f"{base_url}/internal/upload",
        data=body,
        method="POST",
        headers={
            "X-Service-Token": token,
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("code") != 0:
        raise RuntimeError(f"上传封面失败: {payload}")
    return object_name


def register_video(base_url, token, data):
    body = urllib.parse.urlencode(data).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url}/internal/video/register",
        data=body,
        method="POST",
        headers={
            "X-Service-Token": token,
            "Content-Type": "application/x-www-form-urlencoded",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("code") != 0:
        raise RuntimeError(f"登记失败: {payload}")
    return payload.get("data") or {}


def process_one(bvid, args, temp_dir):
    view = fetch_json(f"https://api.bilibili.com/x/web-interface/view?bvid={bvid}")
    title = (view or {}).get("title") or bvid
    duration = int((view or {}).get("duration") or 0)
    cover_object = ""

    if view and view.get("pic"):
        picture_url = view["pic"]
        suffix = os.path.splitext(urllib.parse.urlparse(picture_url).path)[1] or ".jpg"
        picture_path = os.path.join(temp_dir, f"{bvid}{suffix}")
        try:
            request = urllib.request.Request(
                picture_url,
                headers={"User-Agent": USER_AGENT, "Referer": "https://www.bilibili.com/"},
            )
            with urllib.request.urlopen(request, timeout=20) as response, \
                    open(picture_path, "wb") as handle:
                handle.write(response.read())
            content_type = "image/webp" if suffix == ".webp" else "image/jpeg"
            cover_object = upload_object(
                args.minio_base, args.token, "video-cover",
                f"cover/{bvid}{suffix}", picture_path, content_type,
            )
        except Exception as exc:
            print(f"  {bvid} 封面上传失败，继续: {exc}", flush=True)
        finally:
            if os.path.exists(picture_path):
                os.remove(picture_path)

    result = register_video(args.minio_base, args.token, {
        "videoKey": bvid,
        "title": title,
        "description": (view or {}).get("desc") or "",
        "coverObject": cover_object,
        "hlsObject": f"hls/{bvid}/index.m3u8",
        "durationMs": duration * 1000,
    })
    return bvid, result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--namespace", default="vod")
    parser.add_argument("--minio-base", default="http://127.0.0.1:30081")
    parser.add_argument("--token", default="vod-internal-token")
    parser.add_argument("--workers", type=int, default=3)
    args = parser.parse_args()

    prefixes = list_object_prefixes(args.namespace)
    print(f"发现 {len(prefixes)} 个已上传视频前缀", flush=True)

    temp_dir = tempfile.mkdtemp(prefix="vod-backfill-")
    ok = 0
    failed = 0
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = [executor.submit(process_one, bvid, args, temp_dir) for bvid in prefixes]
        for future in as_completed(futures):
            try:
                bvid, result = future.result()
                ok += 1
                print(f"[{ok + failed}/{len(prefixes)}] {bvid} -> videoId={result.get('videoId')} "
                      f"created={result.get('created')}", flush=True)
            except Exception as exc:
                failed += 1
                print(f"[{ok + failed}/{len(prefixes)}] 失败: {exc}", flush=True)

    print(f"结束: 成功 {ok}，失败 {failed}", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
