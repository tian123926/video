#!/usr/bin/env python3
"""
B 站原生 DASH 抓取：不转码、不调用 ffmpeg。

流程：
  1. 取 B 站热门视频列表作为候选；
  2. 取 playurl(fnval=16) 的原始 DASH 流，下载单文件 m4s；
  3. 按 sidx 拆成 init.mp4 与 1.m4s、2.m4s…（索引不落盘），
     以 {作者mid}/{bvid}/video|audio/ 存进 video-hls 桶；
  4. 封面、头像、评论图片分别存入 video-cover / video-avatar / video-comment；
  5. 调用后端 /api/video/internal/import 写入作者、视频、评论，
     同时写入分片数量 / 分片时长 / 编码 / 分辨率，供后端动态生成 index.mpd。

用法：
  python tools/bilibili_dash.py --target 50 --workers 4
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from typing import Any

import requests

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
BASE_HEADERS = {"User-Agent": UA, "Referer": "https://www.bilibili.com/"}
TZ = timezone(timedelta(hours=8))

MAX_ROOTS = 50
MAX_REPLIES = 10
REPLY_FETCH_ROOTS = 6

print_lock = threading.Lock()


def log(message: str) -> None:
    with print_lock:
        print(message, flush=True)


class BiliClient:
    def __init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update(BASE_HEADERS)
        self.lock = threading.Lock()
        try:
            spi = self.session.get("https://api.bilibili.com/x/frontend/finger/spi", timeout=15).json()
            b3 = (spi.get("data") or {}).get("b_3")
            if b3:
                self.session.cookies.set("buvid3", b3, domain=".bilibili.com")
        except Exception:
            pass

    def get_json(self, url: str, params: dict[str, Any] | None = None, retries: int = 3) -> dict[str, Any]:
        last_error: Exception | None = None
        for attempt in range(retries):
            try:
                with self.lock:
                    response = self.session.get(url, params=params, timeout=25)
                if response.status_code == 200:
                    payload = response.json()
                    return payload
                last_error = RuntimeError(f"HTTP {response.status_code}")
            except Exception as exc:  # noqa: BLE001
                last_error = exc
            time.sleep(0.8 * (attempt + 1))
        raise RuntimeError(f"请求失败 {url}: {last_error}")

    def popular(self, page: int, size: int = 50) -> list[dict[str, Any]]:
        payload = self.get_json(
            "https://api.bilibili.com/x/web-interface/popular", {"ps": size, "pn": page}
        )
        return (payload.get("data") or {}).get("list") or []

    def view(self, bvid: str) -> dict[str, Any]:
        payload = self.get_json("https://api.bilibili.com/x/web-interface/view", {"bvid": bvid})
        if payload.get("code") != 0:
            raise RuntimeError(f"view 失败: {payload.get('message')}")
        return payload["data"]

    def playurl(self, bvid: str, cid: int) -> dict[str, Any]:
        payload = self.get_json(
            "https://api.bilibili.com/x/player/playurl",
            {"bvid": bvid, "cid": cid, "fnval": 16, "fnver": 0, "fourk": 1, "qn": 80},
        )
        if payload.get("code") != 0:
            raise RuntimeError(f"playurl 失败: {payload.get('message')}")
        return payload["data"]

    def replies(self, aid: int, page: int = 1, size: int = 20) -> dict[str, Any]:
        payload = self.get_json(
            "https://api.bilibili.com/x/v2/reply",
            {"type": 1, "oid": aid, "pn": page, "ps": min(size, 20), "sort": 2},
        )
        return payload.get("data") or {}

    def sub_replies(self, aid: int, root: int, size: int = 10) -> list[dict[str, Any]]:
        payload = self.get_json(
            "https://api.bilibili.com/x/v2/reply/reply",
            {"type": 1, "oid": aid, "root": root, "pn": 1, "ps": size},
        )
        return ((payload.get("data") or {}).get("replies")) or []

    def follower_count(self, mid: str) -> int:
        """作者粉丝数；失败重试，避免并发抓取时偶发限流写成 0。"""
        for attempt in range(3):
            try:
                payload = self.get_json("https://api.bilibili.com/x/relation/stat", {"vmid": mid})
                if payload.get("code") == 0:
                    return int(((payload.get("data") or {}).get("follower")) or 0)
            except Exception:
                pass
            time.sleep(0.8 * (attempt + 1))
        return 0

    def total_likes(self, mid: str) -> int | None:
        """UP 主获赞总数（/x/space/upstat），接口需要登录时返回 None 以保留库中旧值。"""
        try:
            payload = self.get_json("https://api.bilibili.com/x/space/upstat", {"mid": mid})
            data = payload.get("data") or {}
            if payload.get("code") == 0 and "likes" in data:
                return int(data.get("likes") or 0)
        except Exception:
            pass
        return None


class MinioUploader:
    def __init__(self, base: str, token: str) -> None:
        self.base = base.rstrip("/")
        self.token = token
        self.session = requests.Session()
        self.session.headers.update({"X-Service-Token": token})

    def presign(self, bucket: str, object_name: str, expires: int = 3600) -> str:
        last_error: Exception | None = None
        for attempt in range(3):
            try:
                response = self.session.get(
                    f"{self.base}/internal/presign-put",
                    params={"bucket": bucket, "objectName": object_name, "expires": expires},
                    timeout=90,
                )
                response.raise_for_status()
                payload = response.json()
                data = payload.get("data") or {}
                url = data.get("url")
                if url:
                    return url
                last_error = RuntimeError(f"预签名失败: {payload}")
            except Exception as exc:  # noqa: BLE001
                last_error = exc
            time.sleep(1.2 * (attempt + 1))
        raise RuntimeError(str(last_error))

    def put_file(self, bucket: str, object_name: str, path: str) -> int:
        url = self.presign(bucket, object_name)
        size = os.path.getsize(path)
        with open(path, "rb") as handle:
            response = requests.put(url, data=handle, timeout=600)
        if response.status_code not in (200, 201):
            raise RuntimeError(f"上传失败 {object_name}: HTTP {response.status_code} {response.text[:200]}")
        return size

    def put_bytes(self, bucket: str, object_name: str, content: bytes) -> int:
        url = self.presign(bucket, object_name)
        response = requests.put(url, data=content, timeout=120)
        if response.status_code not in (200, 201):
            raise RuntimeError(f"上传失败 {object_name}: HTTP {response.status_code}")
        return len(content)

    def purge(self, bucket: str | None = None) -> dict[str, Any]:
        params = {"bucket": bucket} if bucket else {}
        response = self.session.post(f"{self.base}/internal/purge", params=params, timeout=300)
        response.raise_for_status()
        return response.json().get("data") or {}

    def download(self, url: str, target: str, retries: int = 2) -> None:
        headers = dict(BASE_HEADERS)
        last_error: Exception | None = None
        for attempt in range(retries):
            try:
                with requests.get(url, headers=headers, stream=True, timeout=180) as response:
                    response.raise_for_status()
                    with open(target, "wb") as handle:
                        for chunk in response.iter_content(chunk_size=1 << 20):
                            if chunk:
                                handle.write(chunk)
                if os.path.getsize(target) > 0:
                    return
            except Exception as exc:  # noqa: BLE001
                last_error = exc
            time.sleep(0.6 * (attempt + 1))
        raise RuntimeError(f"下载失败: {last_error}")


def download_stream(uploader: "MinioUploader", stream: dict[str, Any], target: str) -> int:
    """优先 baseUrl，失败后依次尝试 backupUrl（B 站 CDN 偶发拒连）。"""
    urls = [stream.get("baseUrl")]
    urls.extend(stream.get("backupUrl") or [])
    urls.extend(stream.get("backup_url") or [])
    last_error: Exception | None = None
    for url in urls:
        if not url:
            continue
        try:
            uploader.download(url, target)
            return os.path.getsize(target)
        except Exception as exc:  # noqa: BLE001
            last_error = exc
    raise RuntimeError(f"所有 CDN 均下载失败: {last_error}")


def httpsify(url: str | None) -> str | None:
    if not url:
        return None
    if url.startswith("//"):
        return "https:" + url
    if url.startswith("http://"):
        return "https://" + url[len("http://"):]
    return url


def pick_streams(dash: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    videos = dash.get("video") or []
    audios = dash.get("audio") or []
    if not videos or not audios:
        raise RuntimeError("DASH 流不完整")
    avc = [item for item in videos if str(item.get("codecs", "")).startswith("avc1")]
    pool = avc or videos
    video = max(pool, key=lambda item: (item.get("id", 0), item.get("bandwidth", 0)))
    audio_pool = [item for item in audios if str(item.get("codecs", "")).startswith("mp4a")] or audios
    audio = max(audio_pool, key=lambda item: item.get("bandwidth", 0))
    return video, audio


def seconds_to_duration(seconds: float) -> str:
    return f"PT{seconds:.3f}S"


def build_mpd(video: dict[str, Any], audio: dict[str, Any], duration: float) -> str:
    v_base = video.get("segment_base") or {}
    a_base = audio.get("segment_base") or {}
    frame_rate = video.get("frameRate") or video.get("frame_rate") or "30"
    return f"""<?xml version="1.0" encoding="utf-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
     profiles="urn:mpeg:dash:profile:isoff-on-demand:2011"
     type="static"
     mediaPresentationDuration="{seconds_to_duration(duration)}"
     minBufferTime="PT1.5S">
  <Period id="0" duration="{seconds_to_duration(duration)}">
    <AdaptationSet id="0" contentType="video" mimeType="video/mp4"
                   segmentAlignment="true" subsegmentAlignment="true"
                   startWithSAP="1" subsegmentStartsWithSAP="1">
      <Representation id="v" bandwidth="{int(video.get('bandwidth', 0))}"
                      codecs="{video.get('codecs')}" width="{video.get('width')}" height="{video.get('height')}"
                      frameRate="{frame_rate}">
        <BaseURL>video.m4s</BaseURL>
        <SegmentBase indexRange="{v_base.get('index_range')}">
          <Initialization range="{v_base.get('initialization')}"/>
        </SegmentBase>
      </Representation>
    </AdaptationSet>
    <AdaptationSet id="1" contentType="audio" mimeType="audio/mp4"
                   segmentAlignment="true" startWithSAP="1">
      <Representation id="a" bandwidth="{int(audio.get('bandwidth', 0))}" codecs="{audio.get('codecs')}">
        <BaseURL>audio.m4s</BaseURL>
        <SegmentBase indexRange="{a_base.get('index_range')}">
          <Initialization range="{a_base.get('initialization')}"/>
        </SegmentBase>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>
"""


def strip_emoji_tags(text: str) -> str:
    return re.sub(r"\[[^\]]{1,12}\]", "", text or "").strip()


class Importer:
    def __init__(self, api_base: str, token: str) -> None:
        self.api_base = api_base.rstrip("/")
        self.url = self.api_base + "/api/video/internal/import"
        self.session = requests.Session()
        self.session.headers.update({"X-Service-Token": token, "Content-Type": "application/json"})

    def submit(self, payload: dict[str, Any]) -> dict[str, Any]:
        response = self.session.post(self.url, data=json.dumps(payload), timeout=180)
        if response.status_code != 200:
            raise RuntimeError(f"导入失败 HTTP {response.status_code}: {response.text[:300]}")
        body = response.json()
        if body.get("code") != 0:
            raise RuntimeError(f"导入失败: {body}")
        return body.get("data") or {}

    def submit_stats(self, items: list[dict[str, Any]]) -> dict[str, Any]:
        url = self.api_base + "/api/video/internal/stats"
        response = self.session.post(url, data=json.dumps({"items": items}), timeout=60)
        if response.status_code != 200:
            raise RuntimeError(f"统计回填失败 HTTP {response.status_code}: {response.text[:300]}")
        body = response.json()
        if body.get("code") != 0:
            raise RuntimeError(f"统计回填失败: {body}")
        return body.get("data") or {}

    def submit_comments(self, video_key: str, comments: list[dict[str, Any]]) -> dict[str, Any]:
        url = self.url.rsplit("/import", 1)[0] + "/comments"
        payload = {"videoKey": video_key, "comments": comments}
        response = self.session.post(url, data=json.dumps(payload), timeout=60)
        if response.status_code != 200:
            raise RuntimeError(f"评论回填失败 HTTP {response.status_code}: {response.text[:300]}")
        body = response.json()
        if body.get("code") != 0:
            raise RuntimeError(f"评论回填失败: {body}")
        return body.get("data") or {}


class AvatarCache:
    def __init__(self, uploader: MinioUploader) -> None:
        self.uploader = uploader
        self.done: set[str] = set()
        self.lock = threading.Lock()

    def ensure(self, mid: str | None, face: str | None) -> str | None:
        if not mid or not face:
            return None
        object_name = f"avatar/{mid}.jpg"
        with self.lock:
            if object_name in self.done:
                return object_name
        try:
            content = requests.get(httpsify(face), headers=BASE_HEADERS, timeout=30).content
            if content:
                self.uploader.put_bytes("video-avatar", object_name, content)
        except Exception as exc:  # noqa: BLE001
            log(f"  ! 头像下载失败 mid={mid}: {exc}")
            return None
        with self.lock:
            self.done.add(object_name)
        return object_name


def build_comment_payload(
    client: BiliClient,
    uploader: MinioUploader,
    avatars: AvatarCache,
    aid: int,
    bvid: str,
) -> list[dict[str, Any]]:
    # B 站评论接口 ps 上限为 20，翻 3 页凑满 50 条一级评论
    roots: list[dict[str, Any]] = []
    for page in range(1, 4):
        page_data = client.replies(aid, page=page, size=20)
        batch = page_data.get("replies") or []
        if not batch:
            break
        roots.extend(batch)
        if len(roots) >= MAX_ROOTS:
            break
        time.sleep(0.15)
    roots = roots[:MAX_ROOTS]
    result: list[dict[str, Any]] = []
    for index, root in enumerate(roots):
        member = root.get("member") or {}
        mid = str(member.get("mid") or "")
        content = root.get("content") or {}
        pictures = content.get("pictures") or []
        image_object = None
        if pictures:
            image_url = httpsify(pictures[0].get("img_src"))
            if image_url:
                object_name = f"comment/{bvid}/{root.get('rpid')}.jpg"
                try:
                    binary = requests.get(image_url, headers=BASE_HEADERS, timeout=30).content
                    if binary:
                        uploader.put_bytes("video-comment", object_name, binary)
                        image_object = object_name
                except Exception as exc:  # noqa: BLE001
                    log(f"  ! 评论图片失败 {bvid}: {exc}")

        replies: list[dict[str, Any]] = []
        if index < REPLY_FETCH_ROOTS and int(root.get("rcount") or 0) > 0:
            try:
                children = client.sub_replies(aid, int(root.get("rpid")), MAX_REPLIES)[:MAX_REPLIES]
            except Exception:
                children = root.get("replies") or []
            for child in children[:MAX_REPLIES]:
                child_member = child.get("member") or {}
                child_mid = str(child_member.get("mid") or "")
                replies.append(
                    {
                        "externalId": str(child.get("rpid")),
                        "userExternalId": f"bili:{child_mid}" if child_mid else None,
                        "username": child_member.get("uname") or "B站用户",
                        "avatarObject": avatars.ensure(child_mid, httpsify(child_member.get("avatar"))),
                        "content": strip_emoji_tags((child.get("content") or {}).get("message"))[:1000],
                        "likeCount": int(child.get("like") or 0),
                        "createdAt": datetime.fromtimestamp(int(child.get("ctime") or 0), TZ).isoformat(),
                        "replies": [],
                    }
                )

        result.append(
            {
                "externalId": str(root.get("rpid")),
                "userExternalId": f"bili:{mid}" if mid else None,
                "username": member.get("uname") or "B站用户",
                "avatarObject": avatars.ensure(mid, httpsify(member.get("avatar"))),
                "content": strip_emoji_tags(content.get("message"))[:1000],
                "imageObject": image_object,
                "likeCount": int(root.get("like") or 0),
                "createdAt": datetime.fromtimestamp(int(root.get("ctime") or 0), TZ).isoformat(),
                "replies": [reply for reply in replies if reply["content"]],
            }
        )
    return [item for item in result if item["content"]]


def split_fmp4(path: str, out_dir: str, init_range: str, index_range: str) -> tuple[int, int]:
    """把 B 站单文件 m4s 按 sidx 拆成 init.mp4 与 1.m4s、2.m4s…（不保留索引）"""
    init_start, init_end = (int(value) for value in init_range.split("-"))
    index_start, index_end = (int(value) for value in index_range.split("-"))
    with open(path, "rb") as handle:
        handle.seek(index_start)
        index_box = handle.read(index_end - index_start + 1)
    if index_box[4:8] != b"sidx":
        raise RuntimeError("索引不是 sidx")
    version = index_box[8]
    cursor = 16  # box header + reference_ID
    timescale = int.from_bytes(index_box[cursor:cursor + 4], "big")
    cursor += 4
    cursor += 4 if version == 0 else 8  # earliest_presentation_time
    cursor += 4 if version == 0 else 8  # first_offset
    cursor += 2  # reserved
    count = int.from_bytes(index_box[cursor:cursor + 2], "big")
    cursor += 2
    entries: list[tuple[int, int]] = []
    for _ in range(count):
        size_raw = int.from_bytes(index_box[cursor:cursor + 4], "big")
        duration = int.from_bytes(index_box[cursor + 4:cursor + 8], "big")
        entries.append((size_raw & 0x7FFFFFFF, duration))
        cursor += 12
    if not entries:
        raise RuntimeError("sidx 分片为空")
    os.makedirs(out_dir, exist_ok=True)
    with open(path, "rb") as handle:
        handle.seek(init_start)
        with open(os.path.join(out_dir, "init.mp4"), "wb") as init_file:
            init_file.write(handle.read(init_end - init_start + 1))
        handle.seek(index_end + 1)
        for index, (size, _) in enumerate(entries, start=1):
            with open(os.path.join(out_dir, f"{index}.m4s"), "wb") as segment_file:
                segment_file.write(handle.read(size))
    first_ms = round(entries[0][1] * 1000 / timescale) if timescale else 2500
    return len(entries), max(first_ms, 1)


def upload_segments(uploader: MinioUploader, bucket: str, object_dir: str,
                    track: str, source_dir: str, count: int) -> int:
    """上传 init.mp4 和 1..n.m4s，返回总字节数"""
    total = uploader.put_file(bucket, f"{object_dir}/{track}/init.mp4",
                              os.path.join(source_dir, "init.mp4"))
    for index in range(1, count + 1):
        total += uploader.put_file(bucket, f"{object_dir}/{track}/{index}.m4s",
                                   os.path.join(source_dir, f"{index}.m4s"))
    return total


def process_video(
    client: BiliClient,
    uploader: MinioUploader,
    importer: Importer,
    avatars: AvatarCache,
    entry: dict[str, Any],
    max_duration: int,
) -> dict[str, Any]:
    bvid = entry.get("bvid")
    if not bvid:
        raise RuntimeError("缺少 bvid")
    detail = client.view(bvid)
    duration = int(detail.get("duration") or 0)
    if duration <= 0:
        raise RuntimeError("时长无效")
    if max_duration and duration > max_duration:
        raise RuntimeError(f"跳过：时长 {duration}s 超过限制")

    play = client.playurl(bvid, int(detail["cid"]))
    dash = play.get("dash") or {}
    video_stream, audio_stream = pick_streams(dash)
    dash_duration = float(dash.get("duration") or duration)

    owner = detail.get("owner") or {}
    mid = str(owner.get("mid") or "")
    if not mid:
        raise RuntimeError("缺少作者 mid")

    # 新布局：video-hls/{mid}/{bvid}/video|audio/init.mp4 + 1..n.m4s，不落索引
    object_dir = f"{mid}/{bvid}"
    workdir = tempfile.mkdtemp(prefix=f"bili-{bvid}-")
    video_path = os.path.join(workdir, "video.m4s")
    audio_path = os.path.join(workdir, "audio.m4s")
    video_dir = os.path.join(workdir, "video")
    audio_dir = os.path.join(workdir, "audio")
    try:
        download_stream(uploader, video_stream, video_path)
        download_stream(uploader, audio_stream, audio_path)
        v_base = video_stream.get("segment_base") or {}
        a_base = audio_stream.get("segment_base") or {}
        video_count, video_seg_ms = split_fmp4(
            video_path, video_dir, v_base["initialization"], v_base["index_range"])
        audio_count, audio_seg_ms = split_fmp4(
            audio_path, audio_dir, a_base["initialization"], a_base["index_range"])

        video_size = upload_segments(uploader, "video-hls", object_dir, "video", video_dir, video_count)
        audio_size = upload_segments(uploader, "video-hls", object_dir, "audio", audio_dir, audio_count)

        cover_object = None
        cover_url = httpsify(detail.get("pic"))
        if cover_url:
            try:
                cover_binary = requests.get(cover_url, headers=BASE_HEADERS, timeout=30).content
                if cover_binary:
                    cover_object = f"{mid}/{bvid}.jpg"
                    uploader.put_bytes("video-cover", cover_object, cover_binary)
            except Exception as exc:  # noqa: BLE001
                log(f"  ! 封面失败 {bvid}: {exc}")

        avatar_object = avatars.ensure(mid, httpsify(owner.get("face")))

        stat = detail.get("stat") or {}
        followers = client.follower_count(mid)
        total_likes = client.total_likes(mid)
        comments = build_comment_payload(client, uploader, avatars, int(detail["aid"]), bvid)

        payload = {
            "author": {
                "externalId": f"bili:{mid}",
                "username": owner.get("name") or "UP主",
                "avatarObject": avatar_object,
                "fansCount": followers if followers > 0 else None,
                "likeCount": total_likes,
            },
            "video": {
                "videoKey": bvid,
                "title": detail.get("title") or bvid,
                "description": (detail.get("desc") or "")[:2000],
                "coverObject": cover_object,
                "durationMs": int(dash_duration * 1000),
                "fileSize": video_size + audio_size,
                "viewCount": int(stat.get("view") or 0),
                "likeCount": int(stat.get("like") or 0),
                "publishTime": datetime.fromtimestamp(int(detail.get("pubdate") or 0), TZ).isoformat(),
                "segmentCount": video_count,
                "segmentDurationMs": video_seg_ms,
                "audioSegmentCount": audio_count,
                "audioSegmentDurationMs": audio_seg_ms,
                "videoCodecs": video_stream.get("codecs"),
                "audioCodecs": audio_stream.get("codecs"),
                "width": video_stream.get("width"),
                "height": video_stream.get("height"),
            },
            "comments": comments,
        }
        imported = importer.submit(payload)
        return {
            "bvid": bvid,
            "videoId": imported.get("videoId"),
            "comments": imported.get("comments"),
            "segments": f"{video_count}+{audio_count}",
            "height": video_stream.get("height"),
            "codecs": video_stream.get("codecs"),
            "size": video_size + audio_size,
        }
    finally:
        for directory in (video_dir, audio_dir):
            try:
                if os.path.isdir(directory):
                    for name in os.listdir(directory):
                        os.remove(os.path.join(directory, name))
                    os.rmdir(directory)
            except OSError:
                pass
        for path in (video_path, audio_path):
            try:
                if os.path.exists(path):
                    os.remove(path)
            except OSError:
                pass
        try:
            os.rmdir(workdir)
        except OSError:
            pass


def load_state(path: str) -> set[str]:
    if not os.path.exists(path):
        return set()
    try:
        with open(path, "r", encoding="utf-8-sig") as handle:
            return set(json.load(handle))
    except Exception:
        return set()


def save_state(path: str, done: set[str]) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(sorted(done), handle, ensure_ascii=False, indent=2)


def main() -> int:
    parser = argparse.ArgumentParser(description="B 站原生 DASH 抓取（不使用 ffmpeg）")
    parser.add_argument("--target", type=int, default=100, help="需要导入的视频数量")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--minio-base", default="http://127.0.0.1:30081")
    parser.add_argument("--api-base", default="http://127.0.0.1:30080")
    parser.add_argument("--token", default=os.environ.get("VOD_SERVICE_TOKEN", "vod-internal-token"))
    parser.add_argument("--max-duration", type=int, default=900, help="超过该时长（秒）的视频跳过")
    parser.add_argument("--state", default=os.path.join(os.path.dirname(__file__), "bilibili_state.json"))
    parser.add_argument("--purge", action="store_true", help="先清空 MinIO 演示桶")
    parser.add_argument("--comments-only", action="store_true", help="只回填已导入视频的评论")
    parser.add_argument("--stats-only", action="store_true", help="只回填已导入视频的播放量/点赞数")
    parser.add_argument("--pages", type=int, default=6, help="从热门列表抓取多少页候选")
    args = parser.parse_args()

    uploader = MinioUploader(args.minio_base, args.token)
    if args.purge:
        log("清空 MinIO 桶: " + json.dumps(uploader.purge(), ensure_ascii=False))

    client = BiliClient()
    importer = Importer(args.api_base, args.token)
    avatars = AvatarCache(uploader)
    done = load_state(args.state)

    if args.stats_only:
        def fetch_stats(bvid: str) -> dict[str, Any]:
            detail = client.view(bvid)
            stat = detail.get("stat") or {}
            return {
                "videoKey": bvid,
                "viewCount": int(stat.get("view") or 0),
                "likeCount": int(stat.get("like") or 0),
            }

        items: list[dict[str, Any]] = []
        failed = 0
        with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
            futures = {pool.submit(fetch_stats, bvid): bvid for bvid in sorted(done)}
            for future in as_completed(futures):
                try:
                    items.append(future.result())
                except Exception as exc:  # noqa: BLE001
                    failed += 1
                    log(f"[统计失败] {futures[future]}: {exc}")
        updated = 0
        for start in range(0, len(items), 50):
            result = importer.submit_stats(items[start:start + 50])
            updated += int(result.get("updated") or 0)
        log(f"统计回填完成：抓取 {len(items)} 条，入库 {updated} 条，失败 {failed} 条")
        return 0 if items else 1

    if args.comments_only:
        def backfill(bvid: str) -> str:
            detail = client.view(bvid)
            comments = build_comment_payload(client, uploader, avatars, int(detail["aid"]), bvid)
            result = importer.submit_comments(bvid, comments)
            return f"[评论] {bvid} -> {result.get('comments')} 条"

        success = 0
        failed = 0
        with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
            futures = {pool.submit(backfill, bvid): bvid for bvid in sorted(done)}
            for future in as_completed(futures):
                try:
                    log(future.result())
                    success += 1
                except Exception as exc:  # noqa: BLE001
                    failed += 1
                    log(f"[评论失败] {futures[future]}: {exc}")
        log(f"评论回填完成：成功 {success} 条，失败 {failed} 条")
        return 0 if success else 1

    candidates: list[dict[str, Any]] = []
    seen: set[str] = set()
    for page in range(1, args.pages + 1):
        try:
            entries = client.popular(page)
        except Exception as exc:  # noqa: BLE001
            log(f"热门页 {page} 获取失败: {exc}")
            continue
        for entry in entries:
            bvid = entry.get("bvid")
            if bvid and bvid not in seen:
                seen.add(bvid)
                candidates.append(entry)
        log(f"候选池 {len(candidates)} 条（第 {page} 页）")
        if len(candidates) >= args.target + len(done) + 20:
            break

    pending = [item for item in candidates if item.get("bvid") not in done]
    log(f"已完成 {len(done)} 条，待处理 {len(pending)} 条，目标 {args.target} 条")

    success = 0
    failed = 0
    started = time.time()
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = {}
        for entry in pending[: args.target + 8]:
            futures[pool.submit(process_video, client, uploader, importer, avatars,
                                entry, args.max_duration)] = entry
        for future in as_completed(futures):
            entry = futures[future]
            bvid = entry.get("bvid")
            try:
                result = future.result()
                success += 1
                done.add(bvid)
                save_state(args.state, done)
                log(
                    f"[{success}/{args.target}] {bvid} -> videoId={result['videoId']} "
                    f"{result['height']}p {result['codecs']} "
                    f"{result['size'] / 1024 / 1024:.1f}MB 评论 {result['comments']}"
                )
            except Exception as exc:  # noqa: BLE001
                failed += 1
                log(f"[失败] {bvid}: {exc}")
            if success >= args.target:
                break

    elapsed = time.time() - started
    log(f"完成：成功 {success} 条，失败 {failed} 条，耗时 {elapsed:.1f}s")
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
