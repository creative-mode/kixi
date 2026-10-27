"""Bounded progress handoff and disconnect cancellation at page boundaries."""

import asyncio
import threading
import tracemalloc
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import numpy as np
import pytest

from app.ocr.engine import OCREngine
from app.ocr.postprocessing import ExtractedMetadata


def _engine_with_worker(worker):
    engine = OCREngine.__new__(OCREngine)
    engine._executor = ThreadPoolExecutor(max_workers=1)
    engine.process_images_with_progress = worker
    return engine


@pytest.mark.asyncio
async def test_progress_stream_yields_completed_pages_then_result():
    def worker(images, _request_id, _sources, report, cancelled, _native_text_pages, _page_count, _rasterized):
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


def test_page_iterator_rasterizes_only_the_page_about_to_be_processed(monkeypatch):
    engine = OCREngine.__new__(OCREngine)
    engine.lang = "pt"
    counts = {"yielded": 0, "processed": 0}

    def pages():
        for _ in range(3):
            assert counts["yielded"] == counts["processed"]
            counts["yielded"] += 1
            yield np.zeros((2, 2, 3), dtype=np.uint8)

    def process_image(_image, **_kwargs):
        counts["processed"] += 1
        return SimpleNamespace(
            status="success", questions=[], page_context=None, images_to_upload=[],
            unmapped_content=[], warnings=[],
            document=SimpleNamespace(has_tables=False, main_language="pt"),
            timings={}, metadata=ExtractedMetadata(),
        )

    monkeypatch.setattr(engine, "process_image", process_image)
    result = engine.process_images_with_progress(pages(), page_count=3)

    assert counts == {"yielded": 3, "processed": 3}
    assert result.document.page_count == 3


def test_cancellation_does_not_rasterize_the_next_page(monkeypatch):
    engine = OCREngine.__new__(OCREngine)
    engine.lang = "pt"
    cancelled = threading.Event()
    yielded = []

    def pages():
        for page in range(3):
            yielded.append(page)
            yield np.zeros((2, 2, 3), dtype=np.uint8)

    def process_image(_image, **_kwargs):
        return SimpleNamespace(
            status="success", questions=[], page_context=None, images_to_upload=[],
            unmapped_content=[], warnings=[],
            document=SimpleNamespace(has_tables=False, main_language="pt"),
            timings={}, metadata=ExtractedMetadata(),
        )

    monkeypatch.setattr(engine, "process_image", process_image)
    engine.process_images_with_progress(
        pages(), cancelled=cancelled, page_count=3,
        on_page_complete=lambda _event: cancelled.set(),
    )

    assert yielded == [0]


def test_ten_page_iteration_peak_memory_is_bounded_to_two_rasters(monkeypatch):
    engine = OCREngine.__new__(OCREngine)
    engine.lang = "pt"

    def process_image(_image, **_kwargs):
        return SimpleNamespace(
            status="success", questions=[], page_context=None, images_to_upload=[],
            unmapped_content=[], warnings=[],
            document=SimpleNamespace(has_tables=False, main_language="pt"),
            timings={}, metadata=ExtractedMetadata(),
        )

    monkeypatch.setattr(engine, "process_image", process_image)

    def pages():
        for _ in range(10):
            page = np.empty((1600, 1600, 3), dtype=np.uint8)
            page.fill(1)  # Commit the array pages so the peak is not just virtual memory.
            yield page

    tracemalloc.start()
    try:
        result = engine.process_images_with_progress(pages(), page_count=10)
        _current, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()

    assert result.document.page_count == 10
    assert peak < 20 * 1024 * 1024, f"the iterator retained too much raster memory: {peak} bytes"


@pytest.mark.asyncio
async def test_closing_progress_stream_stops_work_after_current_page():
    processed = []
    worker_done = threading.Event()

    def worker(images, _request_id, _sources, report, cancelled, _native_text_pages, _page_count, _rasterized):
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
