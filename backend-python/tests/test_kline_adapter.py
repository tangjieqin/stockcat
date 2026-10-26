"""适配器单元测试：全部用录制的上游响应，不打真实网络。

夹具来自 2026-10-26 收盘后实测（docs/DATA_SOURCES.md 记录同一批请求）。
"""
from datetime import date, timedelta
from pathlib import Path

import httpx
import pytest

from app.adapters.eastmoney_history import EastMoneyHistoryClient
from app.config import Settings
from app.errors import ApiError

FIXTURES = Path(__file__).parent / "fixtures"


def fixture_text(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def make_client(handler) -> EastMoneyHistoryClient:
    return EastMoneyHistoryClient(Settings(), transport=httpx.MockTransport(handler))


def test_parses_real_upstream_response():
    """字段映射必须与上游一致：第 3 列是收盘价（易错点）。"""
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["params"] = dict(request.url.params)
        return httpx.Response(200, text=fixture_text("push2his_600519_qfq.json"))

    result = make_client(handler).fetch_daily("1.600519", adjust="qfq", limit=250)

    assert len(result.bars) == 11
    last = result.bars[-1]
    assert last.date == date(2026, 10, 26)
    assert last.open == 1325.40
    assert last.close == 1349.98
    assert last.high == 1350.00
    assert last.low == 1324.79
    assert last.volume == 41599
    assert last.amount == 5574287630.00
    assert last.change_percent == 1.12
    assert last.turnover_rate == 0.33
    assert result.skipped == 0
    assert captured["params"]["klt"] == "101"
    assert captured["params"]["fields2"].startswith("f51,f52,f53")


@pytest.mark.parametrize(("adjust", "expected_fqt"), [("raw", "0"), ("qfq", "1"), ("hfq", "2")])
def test_adjust_maps_to_upstream_fqt(adjust: str, expected_fqt: str):
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["params"] = dict(request.url.params)
        return httpx.Response(200, text=fixture_text("push2his_600519_qfq.json"))

    make_client(handler).fetch_daily("1.600519", adjust=adjust, limit=10)
    assert captured["params"]["fqt"] == expected_fqt


def test_limit_truncates_to_tail():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text=fixture_text("push2his_600519_qfq.json"))

    result = make_client(handler).fetch_daily("1.600519", limit=3)
    assert [b.date.isoformat() for b in result.bars] == ["2026-10-22", "2026-10-23", "2026-10-26"]


def test_beg_window_is_wider_than_limit():
    """lmt 参数实测无效，必须靠 beg 限定窗口，且窗口要留冗余。"""
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["params"] = dict(request.url.params)
        return httpx.Response(200, text=fixture_text("push2his_600519_qfq.json"))

    end = date(2026, 10, 26)
    make_client(handler).fetch_daily("1.600519", limit=250, end=end)
    expected_beg = (end - timedelta(days=500)).strftime("%Y%m%d")
    assert captured["params"]["beg"] == expected_beg
    assert captured["params"]["end"] == "20261026"


def test_empty_klines_raises_no_data():
    """实测：北交所代码 rc=0 但 klines=[]，不能当成「没有历史」静默成功。"""

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text=fixture_text("push2his_empty_klines.json"))

    with pytest.raises(ApiError) as excinfo:
        make_client(handler).fetch_daily("0.835185")
    assert excinfo.value.status == 404
    assert excinfo.value.code == "NO_DATA"
    assert "贝特瑞" in excinfo.value.message


def test_rc_100_raises_no_data():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text=fixture_text("push2his_rc100.json"))

    with pytest.raises(ApiError) as excinfo:
        make_client(handler).fetch_daily("1.999999")
    assert excinfo.value.status == 404
    assert excinfo.value.code == "NO_DATA"


def test_upstream_timeout_maps_to_504():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("read timeout", request=request)

    with pytest.raises(ApiError) as excinfo:
        make_client(handler).fetch_daily("1.600519")
    assert excinfo.value.status == 504
    assert excinfo.value.code == "UPSTREAM_TIMEOUT"


def test_upstream_connect_error_maps_to_502():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    with pytest.raises(ApiError) as excinfo:
        make_client(handler).fetch_daily("1.600519")
    assert excinfo.value.status == 502
    assert excinfo.value.code == "UPSTREAM_ERROR"


def test_http_error_status_maps_to_502():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="busy")

    with pytest.raises(ApiError) as excinfo:
        make_client(handler).fetch_daily("1.600519")
    assert excinfo.value.status == 502


def test_malformed_rows_are_counted_not_silently_dropped():
    body = {
        "rc": 0,
        "data": {
            "code": "600519",
            "name": "贵州茅台",
            "klines": [
                "2026-10-23,1,2,3,4,5,6,7,8,9,10",
                "2026-10-24,坏数据,2,3,4,5,6,7,8,9,10",
                "2026-10-26,1,2,3,4,5,6,7,8,9,10",
            ],
        },
    }

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=body)

    result = make_client(handler).fetch_daily("1.600519")
    assert len(result.bars) == 2
    assert result.skipped == 1


def test_all_rows_broken_raises_502():
    body = {"rc": 0, "data": {"name": "某标的", "klines": ["坏数据", "也坏"]}}

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=body)

    with pytest.raises(ApiError) as excinfo:
        make_client(handler).fetch_daily("1.600519")
    assert excinfo.value.status == 502
    assert excinfo.value.code == "INVALID_UPSTREAM_DATA"


def test_duplicate_dates_keep_last_row():
    body = {
        "rc": 0,
        "data": {
            "name": "某标的",
            "klines": [
                "2026-10-26,1,2,3,4,5,6,7,8,9,10",
                "2026-10-26,1,9,9,4,5,6,7,8,9,10",
            ],
        },
    }

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=body)

    result = make_client(handler).fetch_daily("1.600519")
    assert len(result.bars) == 1
    assert result.bars[0].close == 9
    assert result.skipped == 1


def test_non_positive_close_is_preserved_for_java_to_judge():
    """前复权长期历史会出现负价（实测茅台 2001 年）；适配器只搬运，不做业务取舍。"""
    body = {
        "rc": 0,
        "data": {
            "name": "贵州茅台",
            "klines": ["2001-08-27,-312.68,-312.47,-312.03,-313.01,406318,1410347000.00,-0.31,0.26,0.83,56.83"],
        },
    }

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=body)

    result = make_client(handler).fetch_daily("1.600519")
    assert result.bars[0].close == -312.47


def test_invalid_adjust_rejected_before_request():
    def handler(_: httpx.Request) -> httpx.Response:
        raise AssertionError("不应发出请求")

    with pytest.raises(ApiError) as excinfo:
        make_client(handler).fetch_daily("1.600519", adjust="hfq2")
    assert excinfo.value.status == 400


def test_limit_above_max_rejected():
    def handler(_: httpx.Request) -> httpx.Response:
        raise AssertionError("不应发出请求")

    with pytest.raises(ApiError) as excinfo:
        make_client(handler).fetch_daily("1.600519", limit=501)
    assert excinfo.value.status == 400
