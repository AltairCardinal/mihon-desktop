"""HTTP driver and production-event validation for Desktop reader acceptance."""

from __future__ import annotations

import dataclasses
import json
import time
import urllib.error
import urllib.request
from typing import Any


@dataclasses.dataclass(frozen=True)
class ReaderMeasurement:
    duration_millis: float
    phase_millis: dict[str, float]
    io_gate: dict[str, int]


class ReaderContractError(RuntimeError):
    pass


class ReaderTestModeClient:
    def __init__(self, base_url: str, timeout_seconds: float = 10.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    def run_fixture(self, source: str, chapter_id: int) -> ReaderMeasurement:
        self._request("POST", "/test/reset", {})
        self._request(
            "POST",
            "/test/action/read_chapter",
            {
                "readerFixture": source,
                "mangaId": chapter_id,
                "chapterId": chapter_id,
                "pageCount": 180,
                "width": 2400,
                "height": 3500,
                "format": "JPEG",
            },
        )
        deadline = time.monotonic() + self.timeout_seconds
        while True:
            state = self._request("GET", "/test/reader/state")
            events = state.get("productionEvents")
            if isinstance(events, list) and any(
                isinstance(event, dict) and event.get("type") == "FIRST_PAGE_PRESENTED"
                for event in events
            ):
                return validate_reader_state(state, source)
            if time.monotonic() >= deadline:
                raise ReaderContractError(f"{source}: timed out waiting for FIRST_PAGE_PRESENTED")
            time.sleep(0.02)

    def close_reader(self) -> None:
        self._request("POST", "/test/reader/close", {})
        deadline = time.monotonic() + self.timeout_seconds
        while True:
            state = self._request("GET", "/test/reader/state")
            if state.get("productionClosed") is True:
                return
            if time.monotonic() >= deadline:
                raise ReaderContractError("timed out waiting for productionClosed=true")
            time.sleep(0.02)

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=data,
            method=method,
            headers={"Content-Type": "application/json; charset=utf-8"},
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                result = json.loads(response.read().decode("utf-8"))
        except (OSError, urllib.error.URLError, json.JSONDecodeError) as error:
            raise ReaderContractError(f"{method} {path} failed: {error}") from error
        if not isinstance(result, dict):
            raise ReaderContractError(f"{method} {path} returned a non-object response")
        if result.get("success") is False:
            raise ReaderContractError(f"{method} {path} failed: {result.get('error', result)}")
        return result


def validate_reader_state(state: dict[str, Any], source: str) -> ReaderMeasurement:
    fixture = state.get("readerFixture")
    if not isinstance(fixture, dict):
        raise ReaderContractError(f"{source}: readerFixture is missing")
    expected_fixture = {
        "source": source,
        "pageCount": 180,
        "width": 2400,
        "height": 3500,
        "format": "JPEG",
    }
    for field, expected in expected_fixture.items():
        if fixture.get(field) != expected:
            raise ReaderContractError(
                f"{source}: readerFixture.{field} expected {expected!r}, got {fixture.get(field)!r}"
            )

    expected_page_list_calls = 1 if source == "online" else 0
    source_page_list_calls = state.get("sourcePageListCalls")
    if not isinstance(source_page_list_calls, int) or isinstance(source_page_list_calls, bool):
        raise ReaderContractError(
            f"{source}: sourcePageListCalls expected integer {expected_page_list_calls}, "
            f"got {source_page_list_calls!r}"
        )
    if source_page_list_calls != expected_page_list_calls:
        raise ReaderContractError(
            f"{source}: sourcePageListCalls expected {expected_page_list_calls}, "
            f"got {source_page_list_calls}"
        )

    online_image_requests = state.get("onlineImageRequests")
    if not isinstance(online_image_requests, int) or isinstance(online_image_requests, bool):
        raise ReaderContractError(
            f"{source}: onlineImageRequests expected integer, got {online_image_requests!r}"
        )
    if source == "online":
        if online_image_requests not in range(1, 6):
            raise ReaderContractError(
                f"{source}: onlineImageRequests expected 1..5 for the upstream current plus nearby window, "
                f"got {online_image_requests}"
            )
    elif online_image_requests != 0:
        raise ReaderContractError(
            f"{source}: onlineImageRequests expected 0, got {online_image_requests}"
        )

    raw_events = state.get("productionEvents")
    if not isinstance(raw_events, list) or not all(isinstance(event, dict) for event in raw_events):
        raise ReaderContractError(f"{source}: productionEvents must be an object array")
    events: list[dict[str, Any]] = raw_events
    first_matches = [
        (index, event)
        for index, event in enumerate(events)
        if event.get("type") == "FIRST_PAGE_PRESENTED"
    ]
    if len(first_matches) != 1:
        raise ReaderContractError(
            f"{source}: expected exactly one FIRST_PAGE_PRESENTED, found {len(first_matches)}"
        )
    first_index, first_presented = first_matches[0]
    first_page_index = first_presented.get("pageIndex")
    if (
        not isinstance(first_page_index, int)
        or isinstance(first_page_index, bool)
        or first_page_index != 0
    ):
        raise ReaderContractError(
            f"{source}: FIRST_PAGE_PRESENTED.pageIndex expected page 0, got {first_page_index!r}"
        )
    before_first = events[: first_index + 1]
    intent_events = [event for event in before_first if event.get("type") == "OPEN_READER_INTENT"]
    page_lists = [event for event in before_first if event.get("type") == "PAGE_LIST_READY"]
    current_opens = [
        event
        for event in before_first
        if event.get("type") == "OPEN_PAGE" and event.get("pageIndex") == 0
    ]
    current_decodes = [
        event
        for event in before_first
        if event.get("type") == "DECODE"
        and isinstance(event.get("pageIndex"), int)
        and not isinstance(event.get("pageIndex"), bool)
        and event.get("pageIndex") == 0
    ]
    non_current_opens = [
        event
        for event in before_first
        if event.get("type") == "OPEN_PAGE" and event.get("pageIndex") != 0
    ]
    non_current_decodes = [
        event
        for event in before_first
        if event.get("type") == "DECODE" and event not in current_decodes
    ]
    cache_reconciles = [event for event in before_first if event.get("type") == "CACHE_RECONCILE"]
    adjacent_io = [event for event in before_first if event.get("type") == "ADJACENT_IO"]
    required_counts = {
        "OPEN_READER_INTENT": len(intent_events),
        "PAGE_LIST_READY": len(page_lists),
        "OPEN_PAGE(current page)": len(current_opens),
        "DECODE(current page)": len(current_decodes),
    }
    invalid_counts = [
        f"{event_type}={count}"
        for event_type, count in required_counts.items()
        if count != 1
    ]
    if invalid_counts:
        raise ReaderContractError(
            f"{source}: required first-frame events must occur exactly once; "
            + ", ".join(invalid_counts)
        )
    if non_current_opens:
        raise ReaderContractError(f"{source}: non-current OPEN_PAGE occurred before first frame")
    if non_current_decodes:
        raise ReaderContractError(
            f"{source}: non-current or missing pageIndex DECODE occurred before first frame"
        )
    if cache_reconciles:
        raise ReaderContractError(f"{source}: CACHE_RECONCILE occurred before first frame")
    if adjacent_io:
        raise ReaderContractError(f"{source}: ADJACENT_IO occurred before first frame")

    required_events = [
        intent_events[0],
        page_lists[0],
        current_opens[0],
        current_decodes[0],
        first_presented,
    ]
    required_types = [event["type"] for event in required_events]
    event_indexes = [events.index(event) for event in required_events]
    if event_indexes != sorted(event_indexes) or len(set(event_indexes)) != len(event_indexes):
        raise ReaderContractError(
            f"{source}: production event order must be {' -> '.join(required_types)}"
        )
    identities = {(event.get("chapterId"), event.get("generation")) for event in required_events}
    if len(identities) != 1 or next(iter(identities))[0] is None:
        raise ReaderContractError(f"{source}: production events do not share one chapter-generation")

    required_nanos = [_event_nanos(event, source) for event in required_events]
    if required_nanos != sorted(required_nanos):
        raise ReaderContractError(f"{source}: production event timestamps are not monotonic")
    intent_nanos, page_list_nanos, open_nanos, decode_nanos, presented_nanos = required_nanos
    return ReaderMeasurement(
        duration_millis=(presented_nanos - intent_nanos) / 1_000_000.0,
        phase_millis={
            "intentToPageList": (page_list_nanos - intent_nanos) / 1_000_000.0,
            "pageListToOpen": (open_nanos - page_list_nanos) / 1_000_000.0,
            "openToDecode": (decode_nanos - open_nanos) / 1_000_000.0,
            "decodeToPresented": (presented_nanos - decode_nanos) / 1_000_000.0,
        },
        io_gate={
            "pageListReady": len(page_lists),
            "currentPageOpens": len(current_opens),
            "currentPageDecodes": len(current_decodes),
            "nonCurrentPageOpens": len(non_current_opens),
            "nonCurrentPageDecodes": len(non_current_decodes),
            "cacheReconciles": len(cache_reconciles),
            "adjacentIo": len(adjacent_io),
        },
    )


def _event_nanos(event: dict[str, Any], source: str) -> int:
    value = event.get("monotonicNanos")
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ReaderContractError(f"{source}: invalid monotonicNanos for {event.get('type')}")
    return value
