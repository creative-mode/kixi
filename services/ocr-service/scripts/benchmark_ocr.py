#!/usr/bin/env python3
"""Measure local OCR upload and processing latency for image/page-count matrices.

Example:
  python scripts/benchmark_ocr.py \
    --image /tmp/physics-clean.jpg --image /tmp/physics-original.png \
    --image tests/fixtures/ogi_12b.png --pages 1,2 --repeats 5

The script reports client multipart-send time, OCR service processing time, and
the remaining request/response overhead separately. It never prints OCR text.
Use only fixtures approved for the selected OCR endpoint.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import httpx


CHUNK_SIZE = 64 * 1024


@dataclass
class MultipartBody(httpx.SyncByteStream):
    files: list[Path]
    boundary: str
    upload_send_ms: float = 0.0

    def _parts(self) -> Iterator[bytes]:
        for path in self.files:
            yield (
                f"--{self.boundary}\r\n"
                f'Content-Disposition: form-data; name="images"; filename="{path.name}"\r\n'
                "Content-Type: application/octet-stream\r\n\r\n"
            ).encode()
            with path.open("rb") as source:
                while chunk := source.read(CHUNK_SIZE):
                    yield chunk
            yield b"\r\n"
        yield f"--{self.boundary}--\r\n".encode()

    def __iter__(self) -> Iterator[bytes]:
        started = time.perf_counter()
        for chunk in self._parts():
            yield chunk
        self.upload_send_ms = (time.perf_counter() - started) * 1000

    def get_content_length(self) -> int:
        total = len(f"--{self.boundary}--\r\n".encode())
        for path in self.files:
            header = (
                f"--{self.boundary}\r\n"
                f'Content-Disposition: form-data; name="images"; filename="{path.name}"\r\n'
                "Content-Type: application/octet-stream\r\n\r\n"
            ).encode()
            total += len(header) + path.stat().st_size + 2
        return total


def percentile(samples: list[float], fraction: float) -> float:
    ordered = sorted(samples)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=os.getenv("KIXI_OCR_URL", "http://127.0.0.1:8000/ocr/v1/extract"))
    parser.add_argument("--api-key-env", default="KIXI_OCR_API_KEY", help="Environment variable holding the local OCR API key")
    parser.add_argument("--image", action="append", required=True, help="Image fixture; repeat to benchmark different sizes/content")
    parser.add_argument("--pages", default="1,2", help="Comma-separated page counts; each request repeats the selected image")
    parser.add_argument("--repeats", type=int, default=5, help="Requests per image/page-count case (minimum 2)")
    parser.add_argument("--timeout", type=float, default=240.0)
    args = parser.parse_args()
    if args.repeats < 2:
        parser.error("--repeats must be at least 2 to report latency percentiles")
    args.images = [Path(value).expanduser().resolve() for value in args.image]
    missing = [str(path) for path in args.images if not path.is_file()]
    if missing:
        parser.error("image fixture not found: " + ", ".join(missing))
    try:
        args.page_counts = [int(value) for value in args.pages.split(",")]
    except ValueError:
        parser.error("--pages must be comma-separated positive integers")
    if not args.page_counts or any(value < 1 for value in args.page_counts):
        parser.error("--pages must contain positive integers")
    args.api_key = os.getenv(args.api_key_env, "")
    return args


def run_case(client: httpx.Client, url: str, api_key: str, image: Path, page_count: int) -> dict[str, object]:
    body = MultipartBody(files=[image] * page_count, boundary=f"kixi-{uuid.uuid4().hex}")
    headers = {"Content-Type": f"multipart/form-data; boundary={body.boundary}"}
    if api_key:
        headers["X-OCR-API-Key"] = api_key
    started = time.perf_counter()
    response = client.post(url, content=body, headers=headers)
    total_ms = (time.perf_counter() - started) * 1000
    response.raise_for_status()
    payload = response.json()
    processing_ms = float(payload.get("processingTimeMs") or 0)
    if processing_ms <= 0:
        raise RuntimeError("OCR response omitted a positive processingTimeMs")
    return {
        "image": image.name,
        "image_bytes": image.stat().st_size,
        "pages": page_count,
        "status": payload.get("status"),
        "questions": len(payload.get("questions") or []),
        "upload_send_ms": round(body.upload_send_ms, 1),
        "ocr_processing_ms": round(processing_ms, 1),
        "client_overhead_ms": round(total_ms - body.upload_send_ms - processing_ms, 1),
        "client_total_ms": round(total_ms, 1),
    }


def main() -> None:
    args = parse_args()
    observations: list[dict[str, object]] = []
    with httpx.Client(timeout=args.timeout) as client:
        for image in args.images:
            for pages in args.page_counts:
                for attempt in range(args.repeats):
                    sample = run_case(client, args.url, args.api_key, image, pages)
                    sample["attempt"] = attempt + 1
                    observations.append(sample)
                    print(json.dumps(sample, ensure_ascii=False))

    print("SUMMARY")
    grouped: dict[tuple[str, int], list[dict[str, object]]] = {}
    for sample in observations:
        grouped.setdefault((str(sample["image"]), int(sample["pages"])), []).append(sample)
    for (image, pages), samples in grouped.items():
        summary: dict[str, object] = {
            "image": image,
            "image_bytes": samples[0]["image_bytes"],
            "pages": pages,
            "repeats": len(samples),
        }
        for metric in ("upload_send_ms", "ocr_processing_ms", "client_overhead_ms", "client_total_ms"):
            values = [float(sample[metric]) for sample in samples]
            summary[f"{metric}_p50"] = round(statistics.median(values), 1)
            summary[f"{metric}_p95"] = round(percentile(values, 0.95), 1)
        print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
