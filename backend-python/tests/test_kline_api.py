"""内部接口契约测试：用假客户端替代真实网络，只验证参数校验与响应结构。"""
from datetime import date, datetime, timezone

import pytest
from fastapi.testclient import TestClient

from app.adapters.eastmoney_history import get_default_client
from app.errors import ApiError
from app.main import app
from app.schemas import KlineBar

client = TestClient(app)


def _bar(day: int, close: float) -> KlineBar:
    return KlineBar(
        date=date(2026, 10, day),
        open=close - 1,
        high=close + 2,
        low=close - 3,
        close=close,
        volume=1000,
        amount=1_000_000.0,
        change_percent=1.5,
        turnover_rate=0.3,
    )


class FakeClient:
    def __init__(self, bars=None, error: ApiError | None = None):
        self._bars = bars or [_bar(23, 10.0), _bar(26, 11.0)]
        self._error = error
        self.calls: list[dict] = []

    def fetch_daily(self, secid, *, adjust="qfq", limit=250, end=None):
        self.calls.append({"secid": secid, "adjust": adjust, "limit": limit, "end": end})
        if self._error:
            raise self._error
        from app.adapters.eastmoney_history import FetchResult

        return FetchResult(
            secid=secid,
            symbol=secid.split(".", 1)[-1],
            period="daily",
            adjust=adjust,
            requested_limit=limit,
            fetched_at=datetime.now(timezone.utc),
            bars=self._bars[-limit:],
            skipped=0,
        )


@pytest.fixture
def use_fake():
    def _install(fake: FakeClient) -> FakeClient:
        app.dependency_overrides[get_default_client] = lambda: fake
        return fake

    yield _install
    app.dependency_overrides.pop(get_default_client, None)


def test_returns_camel_case_contract(use_fake):
    fake = use_fake(FakeClient())
    resp = client.get("/internal/v1/kline", params={"secid": "1.600519", "limit": 2})

    assert resp.status_code == 200
    body = resp.json()
    assert body["source"] == "eastmoney-push2his"
    assert body["secid"] == "1.600519"
    assert body["symbol"] == "600519"
    assert body["period"] == "daily"
    assert body["adjust"] == "qfq"
    assert body["requestedLimit"] == 2
    assert body["barCount"] == 2
    assert body["skippedBars"] == 0
    assert body["fetchedAt"]
    last = body["bars"][-1]
    assert last["date"] == "2026-10-26"
    assert last["close"] == 11.0
    assert last["changePercent"] == 1.5
    assert last["turnoverRate"] == 0.3
    assert fake.calls[0]["secid"] == "1.600519"


def test_default_limit_is_250(use_fake):
    fake = use_fake(FakeClient())
    client.get("/internal/v1/kline", params={"secid": "1.600519"})
    assert fake.calls[0]["limit"] == 250
    assert fake.calls[0]["adjust"] == "qfq"


def test_rejects_non_daily_period(use_fake):
    use_fake(FakeClient())
    resp = client.get("/internal/v1/kline", params={"secid": "1.600519", "period": "weekly"})
    assert resp.status_code == 400
    body = resp.json()
    assert body["code"] == "NOT_SUPPORTED"
    assert body["path"] == "/internal/v1/kline"


def test_rejects_unsupported_adjust(use_fake):
    use_fake(FakeClient())
    resp = client.get("/internal/v1/kline", params={"secid": "1.600519", "adjust": "hfq2"})
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_ERROR"


def test_rejects_limit_above_max(use_fake):
    use_fake(FakeClient())
    resp = client.get("/internal/v1/kline", params={"secid": "1.600519", "limit": 501})
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_ERROR"


def test_rejects_bad_secid(use_fake):
    use_fake(FakeClient())
    resp = client.get("/internal/v1/kline", params={"secid": "600519"})
    assert resp.status_code == 400
    body = resp.json()
    assert body["code"] == "VALIDATION_ERROR"
    assert body["details"][0]["field"] == "secid"


def test_no_data_maps_to_404_with_unified_body(use_fake):
    use_fake(FakeClient(error=ApiError(404, "NO_DATA", "上游未返回日K数据（贝特瑞(已切换)）")))
    resp = client.get("/internal/v1/kline", params={"secid": "0.835185"})
    assert resp.status_code == 404
    body = resp.json()
    assert body["code"] == "NO_DATA"
    assert body["status"] == 404
    assert body["details"] is None
    assert "贝特瑞" in body["message"]


def test_upstream_timeout_maps_to_504(use_fake):
    use_fake(FakeClient(error=ApiError(504, "UPSTREAM_TIMEOUT", "上游行情源超时")))
    resp = client.get("/internal/v1/kline", params={"secid": "1.600519"})
    assert resp.status_code == 504
    assert resp.json()["code"] == "UPSTREAM_TIMEOUT"


def test_health_still_works():
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"
