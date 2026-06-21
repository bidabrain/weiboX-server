"""WebUI 管理接口（/admin/api）。仅 session 鉴权。

只保留「管理类」：登录、抓取控制、设置、WebDAV。
浏览类（时间线/搜索/主页/评论/关注/图片）走 /api/v1（见 routes_public）。
"""
from __future__ import annotations

from fastapi import APIRouter, Body, Depends, HTTPException, Query, Request, Response
from pydantic import BaseModel

from .. import store
from ..services import repo, webdav
from ..services import tokens as token_service
from ..services.scraper import scraper
from .auth import SESSION_COOKIE, SESSION_MAX_AGE, create_session, require_session, verify_password
from .ratelimit import client_ip, login_limiter


def _is_https(request: Request) -> bool:
    proto = request.headers.get("x-forwarded-proto", request.url.scheme)
    return proto.lower() == "https"

router = APIRouter(prefix="/admin/api")


# ── 登录 ──────────────────────────────────────────────────────────
class LoginBody(BaseModel):
    password: str


@router.post("/login")
def login(body: LoginBody, request: Request, response: Response):
    ip = client_ip(request)
    locked = login_limiter.locked_for(ip)
    if locked > 0:
        raise HTTPException(status_code=429, detail=f"登录尝试过多，请 {int(locked) + 1} 秒后再试")
    if not verify_password(body.password):
        login_limiter.record_fail(ip)
        raise HTTPException(status_code=401, detail="密码错误")
    login_limiter.reset(ip)
    token = create_session()
    response.set_cookie(
        SESSION_COOKIE, token, max_age=SESSION_MAX_AGE,
        httponly=True, samesite="lax", secure=_is_https(request),
    )
    return {"ok": True}


@router.post("/logout")
def logout(request: Request, response: Response):
    response.delete_cookie(SESSION_COOKIE, samesite="lax", secure=_is_https(request))
    return {"ok": True}


@router.get("/session")
def session_check(_: bool = Depends(require_session)):
    return {"ok": True}


auth = [Depends(require_session)]


# ── 抓取控制 ──────────────────────────────────────────────────────
@router.post("/scrape-now", dependencies=auth)
def scrape_now():
    scraper.trigger_now()
    return {"ok": True}


# ── API Token 管理 ────────────────────────────────────────────────
@router.get("/tokens", dependencies=auth)
def list_tokens():
    return {"tokens": token_service.list_tokens()}


@router.post("/tokens", dependencies=auth)
def create_token(payload: dict = Body(default={})):
    label = str((payload or {}).get("label", "")).strip()
    return {"ok": True, "token": token_service.create_token(label)}


@router.delete("/tokens/{tid}", dependencies=auth)
def delete_token(tid: str):
    token_service.delete_token(tid)
    return {"ok": True}


# ── 设置 ──────────────────────────────────────────────────────────
SAFE_KEYS = {
    "cookie", "scrape_enabled", "round_interval_sec", "req_delay_min_sec",
    "req_delay_max_sec", "posts_per_user", "min_check_interval_sec",
    "post_retention_days", "max_cached_posts", "webdav_url", "webdav_user", "webdav_pass",
}


@router.get("/settings", dependencies=auth)
def get_settings():
    return store.all_settings()


@router.post("/settings", dependencies=auth)
def update_settings(payload: dict = Body(...)):
    updates = {k: str(v) for k, v in payload.items() if k in SAFE_KEYS}
    if updates:
        store.set_many(updates)
    return {"ok": True, "settings": store.all_settings()}


# ── WebDAV ────────────────────────────────────────────────────────
@router.post("/webdav/backup", dependencies=auth)
def webdav_backup():
    url = store.get("webdav_url")
    user = store.get("webdav_user")
    pwd = store.get("webdav_pass")
    if not url:
        raise HTTPException(status_code=400, detail="未配置 WebDAV 地址")
    try:
        webdav.backup(url, user, pwd, repo.list_users(), store.get("cookie"))
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))
    return {"ok": True}


@router.post("/webdav/restore", dependencies=auth)
def webdav_restore(import_cookie: bool = Query(False)):
    url = store.get("webdav_url")
    user = store.get("webdav_user")
    pwd = store.get("webdav_pass")
    if not url:
        raise HTTPException(status_code=400, detail="未配置 WebDAV 地址")
    try:
        users, cookie = webdav.restore(url, user, pwd)
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))
    added = repo.replace_all_users(users)
    if import_cookie and cookie:
        store.set_value("cookie", cookie)
    scraper.trigger_now()
    return {"ok": True, "imported_users": len(users), "added": added,
            "cookie_imported": bool(import_cookie and cookie)}
