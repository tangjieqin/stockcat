"""AI 助手接口（P2）。仅供 Java 代理调用，不对前端直接暴露。

契约见 docs/API.md §11 与 docs/PLAN_P2.md。

三个接口：
- ``GET  /agent/v1/health``           配置与工具白名单状态（不探活、不回显 key）
- ``POST /agent/v1/assistant/ask``    一问一答，无会话状态
- ``POST /agent/v1/alerts/explain``   解释单条已触发告警（P2 场景二）
- ``POST /agent/v1/recap/draft``      生成每日复盘草稿，**不落库**（P2 场景三）
- ``POST /agent/v1/diary/refine``     整理一篇日记，**不落库**（P2 场景四）
- ``POST /agent/v1/diary/review``     按关键词回顾日记，只读检索（P2 场景四）
"""
from fastapi import APIRouter

from app.agent import (
    alert_service,
    diary_service,
    java_tools,
    llm,
    recap_service,
    service,
)
from app.config import settings
from app.errors import VALIDATION_ERROR, ApiError
from app.schemas import (
    AgentAlertExplainRequest,
    AgentAlertExplainResponse,
    AgentAskRequest,
    AgentAskResponse,
    AgentDiaryRefineRequest,
    AgentDiaryRefineResponse,
    AgentDiaryReviewRequest,
    AgentDiaryReviewResponse,
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


@router.post("/diary/refine", response_model=AgentDiaryRefineResponse, summary="整理一篇日记（不落库）")
def diary_refine(req: AgentDiaryRefineRequest) -> AgentDiaryRefineResponse:
    """把散乱的日记整理成结构化建议稿。

    **只整理、不保存**：正文写不写回由用户在编辑器里点保存决定。
    涉及标的必须落在 Java 传来的白名单里，越界的一律丢弃并写进 limitations。
    """
    known = [item.model_dump(mode="json", by_alias=True) for item in req.known_instruments]
    return AgentDiaryRefineResponse(
        **diary_service.refine(req.title, req.content, known)
    )


@router.post("/diary/review", response_model=AgentDiaryReviewResponse, summary="按关键词回顾日记")
def diary_review(req: AgentDiaryReviewRequest) -> AgentDiaryReviewResponse:
    """先检索再归纳。

    检索不到就到此为止——让模型"就着关键词聊聊"是最容易编造的一段；
    此时返回 ``empty=true``，前端直接说没找到，不展示任何总结。
    """
    return AgentDiaryReviewResponse(**diary_service.review(req.keyword, req.limit))
