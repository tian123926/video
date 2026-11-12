#!/usr/bin/env python3
"""
ffmpeg / ffprobe 工具服务。

只接受 WORK_ROOT 目录下的文件路径（默认 /vod-transcode），
供转码微服务通过共享 PVC 调用。

接口：
  GET  /health
  POST /ffprobe  {"path": "/vod-transcode/tmp/xx/source.mp4"}
  POST /hls      {"input": "...", "outputDir": "...", "segmentSeconds": 6}
  POST /frame    {"input": "...", "output": "..."}
"""
import json
import os
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

WORK_ROOT = os.path.realpath(os.environ.get("TOOLS_WORK_ROOT", "/vod-transcode"))
PORT = int(os.environ.get("TOOLS_PORT", "8090"))
TIMEOUT_SECONDS = int(os.environ.get("TOOLS_TIMEOUT_MINUTES", "30")) * 60


def safe_path(value, must_exist=True, must_be_file=False):
    if not value or not isinstance(value, str):
        raise ValueError("path 不能为空")
    real = os.path.realpath(value)
    if os.path.commonpath([real, WORK_ROOT]) != WORK_ROOT:
        raise ValueError(f"路径不在允许目录 {WORK_ROOT} 内: {real}")
    if must_exist and not os.path.exists(real):
        raise ValueError(f"路径不存在: {real}")
    if must_be_file and not os.path.isfile(real):
        raise ValueError(f"不是文件: {real}")
    return real


def run_command(command, cwd=None):
    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            capture_output=True,
            text=True,
            timeout=TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"{command[0]} 执行超时（{TIMEOUT_SECONDS} 秒）")
    if result.returncode != 0:
        tail = (result.stdout + result.stderr)[-4000:]
        raise RuntimeError(f"{command[0]} 退出码 {result.returncode}: {tail}")
    return result.stdout


class Handler(BaseHTTPRequestHandler):
    server_version = "vod-tools"

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {"status": "ok"})
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):
        try:
            payload = self._read_json()
            if self.path == "/ffprobe":
                self._handle_ffprobe(payload)
            elif self.path == "/hls":
                self._handle_hls(payload)
            elif self.path == "/frame":
                self._handle_frame(payload)
            else:
                self._send_json(404, {"error": "not found"})
        except ValueError as exc:
            self._send_json(400, {"error": str(exc)})
        except Exception as exc:
            self._send_json(500, {"error": str(exc)})

    def _handle_ffprobe(self, payload):
        path = safe_path(payload.get("path"), must_exist=True, must_be_file=True)
        output = run_command([
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=nw=1:nk=1",
            path,
        ])
        duration = None
        for line in output.splitlines():
            value = line.strip()
            if value:
                duration = float(value)
                break
        if duration is None:
            raise RuntimeError(f"ffprobe 未返回时长: {path}")
        self._send_json(200, {"duration": duration})

    def _handle_hls(self, payload):
        input_path = safe_path(payload.get("input"), must_exist=True, must_be_file=True)
        output_dir = safe_path(payload.get("outputDir"), must_exist=False)
        segment_seconds = int(payload.get("segmentSeconds", 6) or 6)
        os.makedirs(output_dir, exist_ok=True)
        run_command([
            "ffmpeg", "-y",
            "-i", input_path,
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "23",
            "-c:a", "aac",
            "-b:a", "128k",
            "-f", "hls",
            "-hls_time", str(segment_seconds),
            "-hls_playlist_type", "vod",
            "-hls_segment_filename", "seg-%05d.ts",
            "index.m3u8",
        ], cwd=output_dir)
        playlist = os.path.join(output_dir, "index.m3u8")
        if not os.path.isfile(playlist):
            raise RuntimeError(f"ffmpeg 未生成 HLS 清单: {playlist}")
        self._send_json(200, {"ok": True, "playlist": playlist})

    def _handle_frame(self, payload):
        input_path = safe_path(payload.get("input"), must_exist=True, must_be_file=True)
        output_path = safe_path(payload.get("output"), must_exist=False)
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        run_command([
            "ffmpeg", "-y",
            "-i", input_path,
            "-ss", "0",
            "-vframes", "1",
            "-q:v", "2",
            output_path,
        ])
        if not os.path.isfile(output_path):
            raise RuntimeError(f"ffmpeg 未生成首帧文件: {output_path}")
        self._send_json(200, {"ok": True, "output": output_path})

    def _read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw or b"{}")
        except json.JSONDecodeError as exc:
            raise ValueError(f"请求体不是合法 JSON: {exc}")
        if not isinstance(payload, dict):
            raise ValueError("请求体必须是 JSON 对象")
        return payload

    def _send_json(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


def main():
    os.makedirs(WORK_ROOT, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"vod-tools listening on :{PORT}, work root {WORK_ROOT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
