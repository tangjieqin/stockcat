"""基金净值适配器单测：全部用录制的上游响应，不打真实网络。

夹具录制时间：2026-10-26 22:40（收盘后，510300 当日净值已公布 4.1797）。
"""
from datetime import date
from pathlib import Path

import httpx
import pytest

from app.adapters.eastmoney_fund import EastMoneyFundClient
from app.config import Settings
from app.errors import ApiError

FIXTURES = Path(__file__).parent / "fixtures"


def fixture_text(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def make_client(handler) -> EastMoneyFundClient:
    return EastMoneyFundClient(Settings(), transport=httpx.MockTransport(handler))


def test_parses_real_upstream_response():
    """FSRQ/DWJZ/LJJZ/JZZZL 四个字段都要落到对应属性，缺一不可。"""
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["params"] = dict(request.url.params)
        captured["referer"] = request.headers.get("Referer")
        return httpx.Response(200, text=fixture_text("lsjz_510300.json"))

    result = make_client(handler).fetch_nav("510300", limit=5)

    assert result.fund_code == "510300"
    assert result.total_count == 3517
    assert result.skipped == 0
    assert len(result.items) == 5

    latest = result.items[0]
    assert latest.date == date(2026, 10, 26)
    assert latest.nav == 4.1797
    assert latest.acc_nav == 1.8769
    assert latest.change_percent == -0.03

    # 实测：缺 Referer 时上游可能返回空数据，必须固定带上
    assert captured["referer"] == "https://fundf10.eastmoney.com/"
    assert captured["params"]["fundCode"] == "510300"
    assert captured["params"]["pageIndex"] == "1"
    assert captured["params"]["pageSize"] == "5"


def test_empty_list_is_no_data_not_empty_success():
    """实测：非法代码 ErrCode=0 但 LSJZList 为空 —— 不能静默成功。"""

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text=fixture_text("lsjz_empty.json"))

    with pytest.raises(ApiError) as exc:
        make_client(handler).fetch_nav("999999", limit=5)

    assert exc.value.status == 404
    assert exc.value.code == "NO_DATA"


def test_upstream_error_code_is_502():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"ErrCode": 1001, "ErrMsg": "参数错误", "Data": None})

    with pytest.raises(ApiError) as exc:
        make_client(handler).fetch_nav("510300", limit=5)

    assert exc.value.status == 502
    assert exc.value.code == "UPSTREAM_ERROR"


def test_dirty_rows_are_counted_not_silently_dropped():
    payload = {
        "Data": {
            "LSJZList": [
                {"FSRQ": "2026-10-26", "DWJZ": "4.1797", "LJJZ": "1.8769", "JZZZL": "-0.03"},
                {"FSRQ": "", "DWJZ": "bad", "LJJZ": "", "JZZZL": ""},
                {"FSRQ": "2026-10-23", "DWJZ": "4.1811", "LJJZ": "1.8774", "JZZZL": "1.75"},
                {"FSRQ": "2026-10-26", "DWJZ": "4.1798", "LJJZ": "1.8770", "JZZZL": "-0.02"},
            ]
        },
        "ErrCode": 0,
        "TotalCount": 4,
    }

    def handler(request: httpx.Request) -> httpx.Response:
        # 第 2 页返回空，模拟"已经到底了"，否则会一直翻页
        if dict(request.url.params).get("pageIndex") == "2":
            return httpx.Response(200, json={"Data": {"LSJZList": []}, "ErrCode": 0, "TotalCount": 4})
        return httpx.Response(200, json=payload)

    result = make_client(handler).fetch_nav("510300", limit=10)

    assert [p.date.isoformat() for p in result.items] == ["2026-10-26", "2026-10-23"]
    # 脏行 + 重复日期各计一次
    assert result.skipped == 2


def test_paginates_because_page_size_is_capped_at_20():
    """实测：pageSize 传 30/50 上游也只回 20 行，必须靠 pageIndex 翻页。"""
    pages: list[str] = []

    def page_rows(start_day: int, count: int) -> list[dict]:
        return [
            {"FSRQ": f"2026-09-{start_day - i:02d}", "DWJZ": f"{4.0 + i / 1000:.4f}", "LJJZ": "1.8", "JZZZL": "0.1"}
            for i in range(count)
        ]

    def handler(request: httpx.Request) -> httpx.Response:
        params = dict(request.url.params)
        page = params["pageIndex"]
        pages.append(page)
        assert params["pageSize"] == "20", "单页固定 20，多传无意义"
        if page == "1":
            return httpx.Response(200, json={"Data": {"LSJZList": page_rows(30, 20)}, "ErrCode": 0, "TotalCount": 35})
        return httpx.Response(200, json={"Data": {"LSJZList": page_rows(10, 10)}, "ErrCode": 0, "TotalCount": 35})

    result = make_client(handler).fetch_nav("510300", limit=25)

    assert pages == ["1", "2"], "要 25 期要翻两页"
    assert len(result.items) == 25
    assert result.total_count == 35
    # 结果必须按日期倒序，不能是"第 1 页 + 第 2 页"的拼接顺序
    dates = [p.date for p in result.items]
    assert dates == sorted(dates, reverse=True)


def test_stops_when_upstream_ignores_page_index():
    """上游若忽略 pageIndex（每页都返回同样数据），不能无限翻页。"""
    calls = {"n": 0}

    def handler(_: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(200, text=fixture_text("lsjz_510300.json"))

    result = make_client(handler).fetch_nav("510300", limit=100)

    assert calls["n"] == 2, "第 2 页全是重复日期，应立刻停止"
    assert len(result.items) == 5


def test_missing_nav_value_is_dirty_row():
    payload = {
        "Data": {"LSJZList": [{"FSRQ": "2026-10-26", "DWJZ": "--", "LJJZ": "1.8769", "JZZZL": ""}]},
        "ErrCode": 0,
        "TotalCount": 1,
    }

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=payload)

    with pytest.raises(ApiError) as exc:
        make_client(handler).fetch_nav("510300", limit=5)
    assert exc.value.code == "INVALID_UPSTREAM_DATA"


@pytest.mark.parametrize("fund_code", ["51030", "5103000", "abcdef", ""])
def test_invalid_fund_code_is_400(fund_code: str):
    def handler(_: httpx.Request) -> httpx.Response:  # pragma: no cover - 不应被调用
        raise AssertionError("参数非法时不应请求上游")

    with pytest.raises(ApiError) as exc:
        make_client(handler).fetch_nav(fund_code, limit=5)

    assert exc.value.status == 400
    assert exc.value.code == "VALIDATION_ERROR"


def test_limit_over_max_is_400():
    def handler(_: httpx.Request) -> httpx.Response:  # pragma: no cover
        raise AssertionError("超限时不应请求上游")

    with pytest.raises(ApiError) as exc:
        make_client(handler).fetch_nav("510300", limit=999)

    assert exc.value.status == 400


def test_upstream_timeout_maps_to_504():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.TimeoutException("slow", request=request)

    with pytest.raises(ApiError) as exc:
        make_client(handler).fetch_nav("510300", limit=5)

    assert exc.value.status == 504
    assert exc.value.code == "UPSTREAM_TIMEOUT"
