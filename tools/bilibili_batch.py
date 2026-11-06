#!/usr/bin/env python3
"""
批量下载 B 站视频（5-10 分钟）并上传 HLS 到 MinIO。

选择逻辑：
  - 拉取热门列表 + 各分区排行榜，按 duration 过滤 300-600 秒
  - 去重后随机抽取目标数量

执行逻辑：
  - 每个视频调用一次 tools 镜像里的 download-bilibili 脚本
  - 并发执行，单个失败不影响其他任务
  - 状态与日志写到临时目录，可重复运行并断点续传
"""
import argparse
import json
import os
import random
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
HEADERS = {
    "User-Agent": USER_AGENT,
    "Referer": "https://www.bilibili.com/",
    "Accept": "application/json",
}
RID_LIST = (0, 1, 3, 4, 5, 36, 119, 129, 155, 160, 168, 181, 188, 202, 211, 217, 223, 234, 249)


def fetch_json(url, retries=3):
    for _ in range(retries):
        try:
            request = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(request, timeout=20) as response:
                payload = json.loads(response.read().decode("utf-8"))
            if payload.get("code") == 0:
                return payload.get("data") or {}
        except Exception:
            pass
        time.sleep(1.0)
    return {}


def collect_candidates():
    videos = {}

    def take(items):
        for item in items or []:
            bvid = item.get("bvid")
            duration = item.get("duration")
            if not bvid or duration is None:
                continue
            if 300 <= int(duration) <= 600:
                videos.setdefault(bvid, {
                    "bvid": bvid,
                    "title": item.get("title", ""),
                    "duration": int(duration),
                })

    for page in range(1, 51):
        data = fetch_json(f"https://api.bilibili.com/x/web-interface/popular?pn={page}&ps=20")
        take(data.get("list"))
        time.sleep(0.4)

    for rid in RID_LIST:
        data = fetch_json(f"https://api.bilibili.com/x/web-interface/ranking/v2?rid={rid}&type=all")
        take(data.get("list"))
        time.sleep(0.4)

    return list(videos.values())


def run_one(video, args, log_dir, state, state_lock):
    bvid = video["bvid"]
    log_path = os.path.join(log_dir, f"{bvid}.log")
    command = [
        "docker", "run", "--rm",
        "--entrypoint", "/usr/local/bin/download-bilibili",
        "-e", f"VOD_SERVICE_TOKEN={args.token}",
        args.image,
        "--url", f"https://www.bilibili.com/video/{bvid}",
        "--video-id", bvid,
    ]
    if video.get("title"):
        command += ["--title", video["title"]]
    if video.get("duration"):
        command += ["--duration-ms", str(int(video["duration"]) * 1000)]
    command += [
        "--minio-base", args.minio_base,
        "--prefix", f"hls/{bvid}/",
    ]
    with open(log_path, "w", encoding="utf-8") as log:
        log.write(" ".join(command) + "\n\n")
        log.flush()
        process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    status = "ok" if process.returncode == 0 else f"failed({process.returncode})"
    with state_lock:
        state[bvid] = status
        with open(args.state_file, "w", encoding="utf-8") as handle:
            json.dump(state, handle, ensure_ascii=False, indent=2)
    return bvid, status


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", type=int, default=100)
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--image", default="vod-tools:local")
    parser.add_argument("--minio-base", default="http://host.docker.internal:30081")
    parser.add_argument("--token", default="vod-internal-token")
    parser.add_argument("--only-failed", action="store_true",
                        help="只重跑状态文件里失败的任务")
    parser.add_argument("--limit", type=int, default=0,
                        help="本次最多处理多少个任务，0 表示不限制")
    parser.add_argument("--temp",
                        default=os.path.join(tempfile.gettempdir(), "vod-bilibili-batch"))
    args = parser.parse_args()

    os.makedirs(args.temp, exist_ok=True)
    log_dir = os.path.join(args.temp, "logs")
    os.makedirs(log_dir, exist_ok=True)
    args.state_file = os.path.join(args.temp, "state.json")

    state = {}
    if os.path.isfile(args.state_file):
        with open(args.state_file, encoding="utf-8") as handle:
            state = json.load(handle)

    if args.only_failed:
        failed = [bvid for bvid, status in state.items() if status != "ok"]
        pending = []
        for bvid in failed:
            view = fetch_json(
                f"https://api.bilibili.com/x/web-interface/view?bvid={bvid}")
            pending.append({
                "bvid": bvid,
                "title": view.get("title", ""),
                "duration": int(view.get("duration") or 0),
            })
        print(f"重跑失败任务: {len(pending)}", flush=True)
    else:
        print("[1/2] 拉取候选视频", flush=True)
        candidates = collect_candidates()
        random.shuffle(candidates)
        print(f"候选数量: {len(candidates)}", flush=True)
        if len(candidates) < args.target:
            print(f"警告: 符合条件的候选不足 {args.target}，只会处理 {len(candidates)} 个", flush=True)
        pending = [video for video in candidates if state.get(video["bvid"]) != "ok"][:args.target]
        print(f"计划处理: {len(pending)} 个（已完成跳过 "
              f"{sum(1 for v in state.values() if v == 'ok')} 个）", flush=True)

    if args.limit > 0:
        pending = pending[:args.limit]
        print(f"限制本次处理: {len(pending)} 个", flush=True)

    state_lock = threading.Lock()
    done = 0
    failed = 0
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {
            executor.submit(run_one, video, args, log_dir, state, state_lock): video
            for video in pending
        }
        for future in as_completed(futures):
            video = futures[future]
            try:
                bvid, status = future.result()
            except Exception as exc:
                bvid, status = video["bvid"], f"error({exc})"
            done += 1
            if status != "ok":
                failed += 1
            print(f"[{done}/{len(pending)}] {bvid} -> {status} ({video.get('duration', 0)}s)",
                  flush=True)

    print(f"结束: 成功 {done - failed}，失败 {failed}", flush=True)
    print(f"日志目录: {log_dir}", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
