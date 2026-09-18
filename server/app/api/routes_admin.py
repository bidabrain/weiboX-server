"""WebUI 管理接口（/admin/api）。仅 session 鉴权。

只保留「管理类」：登录、抓取控制、设置、WebDAV。
浏览类（时间线/搜索/主页/评论/关注/图片）走 /api/v1（见 routes_public）。
"""
from __future__ import annotations

import os

from fastapi import APIRouter, Body, Depends, HTTPException, Query, Request, Response
from pydantic import BaseModel

from .. import store
from ..config import settings
from ..services import push, repo, webdav
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


# ── 推送（FCM）────────────────────────────────────────────────────
@router.get("/push/status", dependencies=auth)
def push_status():
    """诊断用：私钥是否加载、有哪些设备注册上来了。"""
    key_path = str(settings.firebase_key_path)
    return {
        "enabled": push.enabled(),
        "key_path": key_path,
        "key_exists": os.path.exists(key_path),
        "devices": push.list_devices(),
    }


@router.post("/push/test", dependencies=auth)
def push_test():
    """给所有开启了「特别关注通知」的设备发一条测试推送。

    返回里区分四种失败：私钥没加载 / 没有设备注册 / 设备都静音了 /
    FCM 调用失败，这样 WebUI 能直接指出卡在哪一环，而不是笼统地报「失败」。
    """
    if not push.enabled():
        return {"ok": False, "reason": "disabled", "devices": 0, "sent": 0}
    registered = len(push.list_devices())
    # 测试推送走 special 通道，所以要按该通道的实际收件数统计，
    # 否则在 app 里关掉「特别关注通知」的设备会被算进来、导致误报发送失败
    targets = push.target_count("special")
    if targets == 0:
        reason = "no_devices" if registered == 0 else "all_muted"
        return {"ok": False, "reason": reason, "devices": 0, "registered": registered, "sent": 0}
    sent = push.notify_test()
    return {
        "ok": sent > 0,
        "reason": "" if sent > 0 else "send_failed",
        "devices": targets,
        "sent": sent,
    }


# ── 设置 ──────────────────────────────────────────────────────────
SAFE_KEYS = {
    "cookie", "scrape_enabled", "round_interval_sec", "req_delay_min_sec",
    "req_delay_max_sec", "posts_per_user", "min_check_interval_sec",
    "post_retention_days", "max_cached_posts",
    "hot_enabled", "hot_count", "max_cached_hot", "hot_containerid",
    "webdav_url", "webdav_user", "webdav_pass",
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
    # 恢复特别关注标记（合并语义：只增不清）
    repo.mark_special_batch([u["id"] for u in users if u.get("special")])
    if import_cookie and cookie:
        store.set_value("cookie", cookie)
    scraper.trigger_now()
    return {"ok": True, "imported_users": len(users), "added": added,
            "cookie_imported": bool(import_cookie and cookie)}
