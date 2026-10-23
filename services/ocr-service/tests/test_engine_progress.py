"""Bounded progress handoff and disconnect cancellation at page boundaries."""

import asyncio
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from app.ocr.engine import OCREngine


def _engine_with_worker(worker):
    engine = OCREngine.__new__(OCREngine)
    engine._executor = ThreadPoolExecutor(max_workers=1)
    engine.process_images_with_progress = worker
    return engine


@pytest.mark.asyncio
async def test_progress_stream_yields_completed_pages_then_result():
    def worker(images, _request_id, _sources, report, cancelled, _native_text_pages):
        for page, _image in enumerate(images, start=1):
            if cancelled.is_set():
                break
            report({"page": page, "pageCount": len(images), "timings": {"ocrMs": page * 2.0}})
        return {"status": "success"}

    engine = _engine_with_worker(worker)
    try:
        events = [event async for event in engine.process_images_progress_async([1, 2, 3])]
        assert [event["page"] for event in events[:-1]] == [1, 2, 3]
        assert events[-1] == {"type": "result", "result": {"status": "success"}}
    finally:
        engine._executor.shutdown(wait=True)


@pytest.mark.asyncio
async def test_closing_progress_stream_stops_work_after_current_page():
    processed = []
    worker_done = threading.Event()

    def worker(images, _request_id, _sources, report, cancelled, _native_text_pages):
        try:
            for page, _image in enumerate(images, start=1):
                if cancelled.is_set():
                    break
                processed.append(page)
                report({"page": page, "pageCount": len(images), "timings": {}})
        finally:
            worker_done.set()
        return {"status": "cancelled"}

    engine = _engine_with_worker(worker)
    try:
        stream = engine.process_images_progress_async(list(range(20)))
        first = await anext(stream)
        assert first["page"] == 1
        await stream.aclose()
        await asyncio.wait_for(asyncio.to_thread(worker_done.wait), timeout=1)
        assert 1 <= len(processed) <= 2
    finally:
        engine._executor.shutdown(wait=True)
