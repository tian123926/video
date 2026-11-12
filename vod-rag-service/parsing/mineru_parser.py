"""MinerU 本地解析 PDF -> markdown + content_list.json + images/"""
import subprocess
from pathlib import Path

from config import MINERU_API_URL, MINERU_BACKEND, MINERU_OUTPUT_DIR


def parse_pdf_with_mineru(pdf_path: str,
                          output_dir: str = MINERU_OUTPUT_DIR,
                          backend: str = MINERU_BACKEND) -> Path:
    """
    调用本地 MinerU CLI 解析 PDF, 返回生成的 markdown 文件路径。
    backend: "pipeline"(默认, CPU 可跑) / "vlm-engine"(需 GPU) / "hybrid-engine"
    MINERU_API_URL 非空时复用常驻 mineru-api 服务, 不再每次起临时进程。
    """
    pdf_path, output_dir = Path(pdf_path), Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    cmd = ["mineru", "-p", str(pdf_path), "-o", str(output_dir), "-b", backend]
    if MINERU_API_URL:
        cmd += ["--api-url", MINERU_API_URL]
    print(f"[MinerU] 执行: {' '.join(cmd)}")
    subprocess.run(cmd, check=True)

    # MinerU 输出命名: {stem}.md, {stem}_content_list.json, images/ 等
    md_files = list(output_dir.rglob(f"{pdf_path.stem}.md")) \
        or list(output_dir.rglob("*.md"))
    if not md_files:
        raise FileNotFoundError(f"未在 {output_dir} 下找到 MinerU 输出的 markdown")
    return md_files[0].resolve()
