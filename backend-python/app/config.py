"""Python 服务运行配置。

全部可通过环境变量覆盖，默认值面向本地开发，不包含任何密钥。
"""
import os
from dataclasses import dataclass

DEFAULT_HISTORY_BASE_URL = "https://push2his.eastmoney.com"
DEFAULT_FUND_BASE_URL = "https://api.fund.eastmoney.com"


def _float_env(name: str, default: float) -> float:
    raw = os.getenv(name)
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _int_env(name: str, default: int) -> int:
    raw = os.getenv(name)
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    """历史行情 / 基金净值适配器配置。"""

    history_base_url: str = DEFAULT_HISTORY_BASE_URL
    history_timeout_seconds: float = 8.0
    # 单次返回条数上限：前复权长期历史会出现负价（实测），故限制窗口长度
    history_max_limit: int = 500
    fund_base_url: str = DEFAULT_FUND_BASE_URL
    fund_timeout_seconds: float = 10.0
    # 净值历史深度：一年约 250 期，够算区间收益；不做全历史
    fund_max_limit: int = 250

    @staticmethod
    def from_env() -> "Settings":
        return Settings(
            history_base_url=os.getenv("STOCKCAT_HISTORY_BASE_URL", DEFAULT_HISTORY_BASE_URL),
            history_timeout_seconds=_float_env("STOCKCAT_HISTORY_TIMEOUT_SECONDS", 8.0),
            history_max_limit=_int_env("STOCKCAT_HISTORY_MAX_LIMIT", 500),
            fund_base_url=os.getenv("STOCKCAT_FUND_BASE_URL", DEFAULT_FUND_BASE_URL),
            fund_timeout_seconds=_float_env("STOCKCAT_FUND_TIMEOUT_SECONDS", 10.0),
            fund_max_limit=_int_env("STOCKCAT_FUND_MAX_LIMIT", 250),
        )


settings = Settings.from_env()
