"""鉴权：WebUI 用签名 session cookie（密码登录），对外 API 用 Bearer Token。"""
from __future__ import annotations

import hmac
import time

from fastapi import Cookie, Depends, Header, HTTPException, status
from itsdangerous import BadSignature, SignatureExpired, URLSafeTimedSerializer

from ..config import settings

SESSION_COOKIE = "weibox_session"
SESSION_MAX_AGE = 7 * 24 * 3600  # 7 天

_serializer = URLSafeTimedSerializer(settings.secret_key, salt="weibox-admin-session")


def verify_password(password: str) -> bool:
    return hmac.compare_digest(password or "", settings.admin_password)


def create_session() -> str:
    return _serializer.dumps({"role": "admin", "ts": int(time.time())})


def _session_valid(token: str) -> bool:
    try:
        _serializer.loads(token, max_age=SESSION_MAX_AGE)
        return True
    except (BadSignature, SignatureExpired):
        return False


def require_session(weibox_session: str = Cookie(default="")) -> bool:
    """WebUI 后端依赖：校验登录态。"""
    if not weibox_session or not _session_valid(weibox_session):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="未登录")
    return True


def _token_valid(authorization: str) -> bool:
    token = ""
    if authorization.startswith("Bearer "):
        token = authorization[7:].strip()
    elif authorization:
        token = authorization.strip()
    if not token:
        return False
    # 可选的环境变量「主令牌」
    if settings.api_token and hmac.compare_digest(token, settings.api_token):
        return True
    # WebUI 里管理的数据库令牌
    from ..services import tokens as token_service
    return token_service.is_valid_token(token)


def require_api_token(authorization: str = Header(default="")) -> bool:
    """对外 API 依赖：仅校验 Bearer Token。"""
    if not _token_valid(authorization):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="无效的 API Token")
    return True


def require_session_or_token(
    weibox_session: str = Cookie(default=""),
    authorization: str = Header(default=""),
) -> bool:
    """浏览类 API 依赖：WebUI 的 session cookie 或 app 的 Bearer Token 任一有效即可。"""
    if weibox_session and _session_valid(weibox_session):
        return True
    if _token_valid(authorization):
        return True
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="需要登录或有效 Token")
