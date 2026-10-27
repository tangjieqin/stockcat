"""AI 助手接口（P2）。仅供 Java 代理调用，不对前端直接暴露。

契约见 docs/API.md §11 与 docs/PLAN_P2.md。

三个接口：
- ``GET  /agent/v1/health``           配置与工具白名单状态（不探活、不回显 key）
- ``POST /agent/v1/assistant/ask``    一问一答，无会话状态
- ``POST /agent/v1/alerts/explain``   解释单条已触发告警（P2 场景二）
- ``POST /agent/v1/recap/draft``      生成每日复盘草稿，**不落库**（P2 场景三）
"""
from fastapi import APIRouter

from app.agent import alert_service, java_tools, llm, recap_service, service
from app.config import settings
from app.errors import VALIDATION_ERROR, ApiError
from app.schemas import (
    AgentAlertExplainRequest,
    AgentAlertExplainResponse,
    AgentAskRequest,
    AgentAskResponse,
    AgentHealthResponse,
    AgentRecapDraftRequest,
    AgentRecapDraftResponse,
)

router = APIRouter(prefix="/agent/v1", tags=["agent"])


@router.get("/health", response_model=AgentHealthResponse, summary="Agent 配置状态")
def health() -> AgentHealthResponse:
    return AgentHealthResponse(
        status="UP",
        model_configured=llm.is_configured(),
        model=settings.llm_model or None,
        tools=java_tools.tool_names(),
    )


@router.post("/assistant/ask", response_model=AgentAskResponse, summary="AI 助手一问一答")
def ask(req: AgentAskRequest) -> AgentAskResponse:
    question = (req.question or "").strip()
    if not question:
        raise ApiError(400, VALIDATION_ERROR, "question 不能为空")
    if len(question) > settings.agent_question_max_chars:
        raise ApiError(
            400,
            VALIDATION_ERROR,
            f"question 不能超过 {settings.agent_question_max_chars} 字",
            [{"field": "question", "reason": f"最大 {settings.agent_question_max_chars} 字"}],
        )
    return AgentAskResponse(**service.ask(question))


@router.post("/alerts/explain", response_model=AgentAlertExplainResponse, summary="解释单条告警事件")
def explain_alert(req: AgentAlertExplainRequest) -> AgentAlertExplainResponse:
    """解释一条已触发的事件。

    数据由 Java 读取并组装（事件快照 + 当前行情/指标），这里只做解释：
    不查库、不重新计算指标——重新计算会用"今天的数据"解释"当时的触发"。
    """
    if not req.event.rule_type:
        raise ApiError(400, VALIDATION_ERROR, "event.ruleType 不能为空")
    payload = req.model_dump(mode="json", by_alias=True)
    return AgentAlertExplainResponse(**alert_service.explain(payload))


@router.post("/recap/draft", response_model=AgentRecapDraftResponse, summary="生成每日复盘草稿（不落库）")
def recap_draft(req: AgentRecapDraftRequest) -> AgentRecapDraftResponse:
    """按指定日期生成一份复盘草稿。

    **只生成、不保存**：落库与「AI_DRAFT → MANUAL」确认在 Java 侧，
    这里不碰数据库，也不决定草稿最终长什么样——用户编辑后才成为正式复盘。
    """
    return AgentRecapDraftResponse(**recap_service.generate(req.date))
