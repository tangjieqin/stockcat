"""统一错误响应。

结构与 Java 侧 docs/API.md §1 保持一致，便于 Java 直接透传上游失败原因：
{"timestamp":"...","status":404,"code":"NO_DATA","message":"...","details":null,"path":"..."}
"""
from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

# 错误码：与 Java 侧映射关系见 backend-python/README.md §错误码
VALIDATION_ERROR = "VALIDATION_ERROR"
NOT_SUPPORTED = "NOT_SUPPORTED"
NO_DATA = "NO_DATA"
UPSTREAM_ERROR = "UPSTREAM_ERROR"
UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT"
INVALID_UPSTREAM_DATA = "INVALID_UPSTREAM_DATA"
INTERNAL_ERROR = "INTERNAL_ERROR"


class ApiError(Exception):
    """业务错误：由适配器/路由抛出，统一交给异常处理器序列化。"""

    def __init__(
        self,
        status: int,
        code: str,
        message: str,
        details: list[dict[str, Any]] | None = None,
    ) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.details = details


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _body(
    status: int,
    code: str,
    message: str,
    path: str,
    details: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    return {
        "timestamp": _now_iso(),
        "status": status,
        "code": code,
        "message": message,
        "details": details,
        "path": path,
    }


def register_exception_handlers(app: FastAPI) -> None:
    """挂载统一错误处理；不返回堆栈与内部配置。"""

    @app.exception_handler(ApiError)
    async def _handle_api_error(request: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status,
            content=_body(exc.status, exc.code, exc.message, request.url.path, exc.details),
        )

    @app.exception_handler(RequestValidationError)
    async def _handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        details = [
            {
                "field": ".".join(str(part) for part in err.get("loc", ()) if part != "query"),
                "reason": err.get("msg", ""),
            }
            for err in exc.errors()
        ]
        return JSONResponse(
            status_code=400,
            content=_body(400, VALIDATION_ERROR, "请求参数不合法", request.url.path, details),
        )

    @app.exception_handler(Exception)
    async def _handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(
            status_code=500,
            content=_body(500, INTERNAL_ERROR, "服务内部错误", request.url.path),
        )
