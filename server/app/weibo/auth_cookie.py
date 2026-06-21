"""统一解析「有效 cookie」：优先用配置的 cookie，否则用浏览器引导的访客 session。

供抓取调度与 WebUI 后端（搜索/补全资料）共用，保证两边登录态一致。
"""
from __future__ import annotations

from typing import Tuple

from .. import store
from ..config import settings
from .client import WeiboClient
from .visitor import visitor_session


async def effective_cookie(force_visitor: bool = False) -> Tuple[str, bool]:
    """返回 (cookie, is_visitor)。"""
    user_cookie = store.get("cookie").strip()
    if user_cookie and not force_visitor:
        return user_cookie, False
    if settings.enable_browser:
        try:
            vc = await visitor_session.get(force=force_visitor)
            if vc:
                return vc, True
        except Exception:
            pass
    return user_cookie, False


async def new_client(force_visitor: bool = False) -> WeiboClient:
    cookie, _ = await effective_cookie(force_visitor=force_visitor)
    return WeiboClient(cookie)
