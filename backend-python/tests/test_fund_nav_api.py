"""内部基金净值接口契约测试：假客户端替代真实网络。"""
from datetime import date, datetime, timezone

import pytest
from fastapi.testclient import TestClient

from app.adapters.eastmoney_fund import NavFetchResult, get_default_client
from app.errors import ApiError
from app.main import app
from app.schemas import FundNavPoint

client = TestClient(app)


class FakeClient:
    def __init__(self, items=None, error: ApiError | None = None):
        self._items = items or [
            FundNavPoint(date=date(2026, 10, 26), nav=4.1797, acc_nav=1.8769, change_percent=-0.03),
            FundNavPoint(date=date(2026, 10, 23), nav=4.1811, acc_nav=1.8774, change_percent=1.75),
        ]
        self._error = error
        self.calls: list[dict] = []

    def fetch_nav(self, fund_code, *, limit=30):
        self.calls.append({"fundCode": fund_code, "limit": limit})
        if self._error:
            raise self._error
        return NavFetchResult(
            fund_code=fund_code,
            requested_limit=limit,
            fetched_at=datetime.now(timezone.utc),
            total_count=3517,
            items=self._items[:limit],
            skipped=0,
        )


@pytest.fixture()
def fake_client():
    original = app.dependency_overrides.get(get_default_client)

    def _install(fake: FakeClient) -> FakeClient:
        app.dependency_overrides[get_default_client] = lambda: fake
        return fake

    yield _install
    if original is None:
        app.dependency_overrides.pop(get_default_client, None)
    else:
        app.dependency_overrides[get_default_client] = original


def test_returns_items_with_camel_case(fake_client):
    fake = fake_client(FakeClient())

    resp = client.get("/internal/v1/fund-nav", params={"fundCode": "510300", "limit": 2})

    assert resp.status_code == 200
    body = resp.json()
    assert body["source"] == "eastmoney-f10-lsjz"
    assert body["fundCode"] == "510300"
    assert body["requestedLimit"] == 2
    assert body["totalCount"] == 3517
    assert body["count"] == 2
    assert body["skippedRows"] == 0
    first = body["items"][0]
    assert first["date"] == "2026-10-26"
    assert first["nav"] == 4.1797
    assert first["accNav"] == 1.8769
    assert first["changePercent"] == -0.03
    assert fake.calls == [{"fundCode": "510300", "limit": 2}]


def test_no_data_is_404(fake_client):
    fake_client(FakeClient(error=ApiError(404, "NO_DATA", "上游未返回净值数据")))

    resp = client.get("/internal/v1/fund-nav", params={"fundCode": "999999"})

    assert resp.status_code == 404
    assert resp.json()["code"] == "NO_DATA"


def test_fund_code_pattern_is_validated(fake_client):
    fake_client(FakeClient())

    resp = client.get("/internal/v1/fund-nav", params={"fundCode": "sh510300"})

    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_ERROR"


def test_limit_over_max_is_400(fake_client):
    fake = fake_client(FakeClient())

    resp = client.get("/internal/v1/fund-nav", params={"fundCode": "510300", "limit": 999})

    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_ERROR"
    assert fake.calls == []
