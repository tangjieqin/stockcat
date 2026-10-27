"""Python 服务运行配置。

全部可通过环境变量覆盖，默认值面向本地开发，不包含任何密钥。
"""
import os
from dataclasses import dataclass

DEFAULT_HISTORY_BASE_URL = "https://push2his.eastmoney.com"
DEFAULT_FUND_BASE_URL = "https://api.fund.eastmoney.com"
DEFAULT_JAVA_BASE_URL = "http://127.0.0.1:8081"


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
    """历史行情 / 基金净值适配器 + P2 Agent 配置。"""

    history_base_url: str = DEFAULT_HISTORY_BASE_URL
    history_timeout_seconds: float = 8.0
    # 单次返回条数上限：前复权长期历史会出现负价（实测），故限制窗口长度
    history_max_limit: int = 500
    fund_base_url: str = DEFAULT_FUND_BASE_URL
    fund_timeout_seconds: float = 10.0
    # 净值历史深度：一年约 250 期，够算区间收益；不做全历史
    fund_max_limit: int = 250

    # ---- P2 Agent ----
    # Java 服务地址：Agent 的全部业务数据都从这里取，Python 不连数据库
    java_base_url: str = DEFAULT_JAVA_BASE_URL
    java_timeout_seconds: float = 5.0
    # OpenAI 兼容协议：base_url 指向 ".../v1"，换供应商只改这三个值
    llm_base_url: str = ""
    llm_model: str = ""
    llm_api_key: str = ""
    llm_timeout_seconds: float = 45.0
    # 单次提问最多调用几个工具（防止一个问题把整库拉一遍）
    agent_max_tool_calls: int = 4
    agent_question_max_chars: int = 500

    @property
    def llm_configured(self) -> bool:
        """三个值都填了才算配好；只填一半时按未配置处理，避免半死的调用。"""
        return bool(self.llm_base_url and self.llm_model and self.llm_api_key)

    @staticmethod
    def from_env() -> "Settings":
        return Settings(
            history_base_url=os.getenv("STOCKCAT_HISTORY_BASE_URL", DEFAULT_HISTORY_BASE_URL),
            history_timeout_seconds=_float_env("STOCKCAT_HISTORY_TIMEOUT_SECONDS", 8.0),
            history_max_limit=_int_env("STOCKCAT_HISTORY_MAX_LIMIT", 500),
            fund_base_url=os.getenv("STOCKCAT_FUND_BASE_URL", DEFAULT_FUND_BASE_URL),
            fund_timeout_seconds=_float_env("STOCKCAT_FUND_TIMEOUT_SECONDS", 10.0),
            fund_max_limit=_int_env("STOCKCAT_FUND_MAX_LIMIT", 250),
            java_base_url=os.getenv("STOCKCAT_JAVA_BASE_URL", DEFAULT_JAVA_BASE_URL),
            java_timeout_seconds=_float_env("STOCKCAT_JAVA_TIMEOUT_SECONDS", 5.0),
            llm_base_url=os.getenv("STOCKCAT_LLM_BASE_URL", "").strip(),
            llm_model=os.getenv("STOCKCAT_LLM_MODEL", "").strip(),
            llm_api_key=os.getenv("STOCKCAT_LLM_API_KEY", "").strip(),
            llm_timeout_seconds=_float_env("STOCKCAT_LLM_TIMEOUT_SECONDS", 45.0),
            agent_max_tool_calls=_int_env("STOCKCAT_AGENT_MAX_TOOL_CALLS", 4),
            agent_question_max_chars=_int_env("STOCKCAT_AGENT_QUESTION_MAX_CHARS", 500),
        )


settings = Settings.from_env()
