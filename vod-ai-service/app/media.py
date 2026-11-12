"""ffmpeg 封装：探测时长、抽音频、抽关键帧。"""

import json
import logging
import os
import shutil
import subprocess

from . import config

logger = logging.getLogger(__name__)


def _run(cmd: list[str]) -> None:
    logger.info("ffmpeg: %s", " ".join(cmd))
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg 执行失败({proc.returncode}): {proc.stderr[-800:]}")


def probe_duration_ms(video_path: str) -> int:
    cmd = [
        "ffprobe", "-v", "error", "-show_entries", "format=duration",
        "-of", "json", video_path,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        return 0
    try:
        data = json.loads(proc.stdout or "{}")
        return int(float(data.get("format", {}).get("duration", 0)) * 1000)
    except (ValueError, TypeError):
        return 0


def extract_audio(video_path: str, wav_path: str, max_seconds: int | None = None) -> str:
    os.makedirs(os.path.dirname(wav_path), exist_ok=True)
    cmd = ["ffmpeg", "-y", "-v", "error", "-i", video_path, "-vn", "-ac", "1", "-ar", "16000"]
    if max_seconds:
        cmd += ["-t", str(max_seconds)]
    cmd += ["-f", "wav", wav_path]
    _run(cmd)
    return wav_path


def merge_dash(client, prefix: str, work_dir: str, out_path: str) -> None:
    """合并 video-hls/{prefix}/video|audio 下的 init.mp4 + N.m4s 为单个可播放 mp4。"""
    inputs: list[str] = []
    for track in ("video", "audio"):
        track_prefix = f"{prefix}/{track}/"
        names = [
            item.object_name
            for item in client.list_objects(config.HLS_BUCKET, prefix=track_prefix, recursive=True)
        ]
        segments = sorted(
            (name for name in names if name.endswith(".m4s")),
            key=lambda name: int(os.path.splitext(os.path.basename(name))[0]),
        )
        init = f"{track_prefix}init.mp4"
        if init not in names or not segments:
            logger.warning("HLS 轨道不完整，跳过: %s", track_prefix)
            continue
        # fMP4 片段按 init + 序号顺序二进制拼接即为完整轨道
        fragmented = os.path.join(work_dir, f"{track}.fmp4")
        with open(fragmented, "wb") as handle:
            for name in [init, *segments]:
                with client.get_object(config.HLS_BUCKET, name) as response:
                    shutil.copyfileobj(response, handle)
        inputs.append(fragmented)
    if not inputs:
        raise RuntimeError(f"HLS 分片不可用: {prefix}")
    cmd = ["ffmpeg", "-y", "-v", "error"]
    for path in inputs:
        cmd += ["-i", path]
    if len(inputs) == 2:
        cmd += ["-map", "0:v:0", "-map", "1:a:0"]
    # out_path 无扩展名，必须显式指定容器格式
    cmd += ["-c", "copy", "-movflags", "+faststart", "-f", "mp4", out_path]
    try:
        _run(cmd)
    finally:
        for path in inputs:
            try:
                os.remove(path)
            except OSError:
                pass


def extract_frames(video_path: str, out_dir: str, interval_sec: float, max_frames: int) -> list[dict]:
    os.makedirs(out_dir, exist_ok=True)
    pattern = os.path.join(out_dir, "%05d.jpg")
    cmd = [
        "ffmpeg", "-y", "-v", "error", "-i", video_path,
        "-vf", f"fps=1/{interval_sec},scale={config.FRAME_WIDTH}:-2",
        "-q:v", "3", "-frames:v", str(max_frames), pattern,
    ]
    _run(cmd)

    frames = []
    for name in sorted(os.listdir(out_dir)):
        if not name.endswith(".jpg"):
            continue
        index = int(name.split(".")[0])
        frames.append(
            {
                "seq": index,
                "ts_ms": int((index - 1) * interval_sec * 1000),
                "path": os.path.join(out_dir, name),
            }
        )
    return frames
