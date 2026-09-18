"""FCM 推送（新版 HTTP v1，用服务账号私钥）。

私钥文件存在则启用；否则所有发送静默跳过（不影响其余功能）。
设备 token 由 app 上报、存数据库；发送失败的失效 token 自动清理。
"""
from __future__ import annotations

import os
import time
from typing import List, Optional

from sqlalchemy import select

from ..config import settings
from ..db import get_session
from ..models import DeviceToken

_initialized = False
_enabled = False


def init() -> None:
    """启动时调用：私钥存在则初始化 firebase-admin。"""
    global _initialized, _enabled
    if _initialized:
        return
    _initialized = True
    path = str(settings.firebase_key_path)
    if not os.path.exists(path):
        _enabled = False
        return
    try:
        import firebase_admin
        from firebase_admin import credentials
        if not firebase_admin._apps:
            firebase_admin.initialize_app(credentials.Certificate(path))
        _enabled = True
    except Exception:
        _enabled = False


def enabled() -> bool:
    return _enabled


def _now_ms() -> int:
    return int(time.time() * 1000)


# ── 设备 token 管理 ───────────────────────────────────────────────
def register_device(
    token: str,
    label: str = "",
    notif_captcha: bool = True,
    notif_special: bool = True,
) -> None:
    if not token:
        return
    with get_session() as s:
        row = s.get(DeviceToken, token)
        if row is None:
            s.add(DeviceToken(
                token=token, label=label, created_at=_now_ms(), last_seen=_now_ms(),
                notif_captcha=notif_captcha, notif_special=notif_special,
            ))
        else:
            row.last_seen = _now_ms()
            if label:
                row.label = label
            row.notif_captcha = notif_captcha
            row.notif_special = notif_special
        s.commit()


def unregister_device(token: str) -> None:
    with get_session() as s:
        row = s.get(DeviceToken, token)
        if row:
            s.delete(row)
            s.commit()


def list_device_tokens() -> List[str]:
    with get_session() as s:
        return [r.token for r in s.execute(select(DeviceToken)).scalars().all()]


def _tokens_for(kind: str) -> List[str]:
    """返回开启了某类通知的设备 token。kind: 'captcha' | 'special'。"""
    col = DeviceToken.notif_special if kind == "special" else DeviceToken.notif_captcha
    with get_session() as s:
        return [
            r.token for r in
            s.execute(select(DeviceToken).where(col.is_(True))).scalars().all()
        ]


def device_count() -> int:
    with get_session() as s:
        return len(s.execute(select(DeviceToken)).scalars().all())


def target_count(kind: str) -> int:
    """某类通知的实际收件设备数（已排除在 app 里关掉该开关的设备）。"""
    return len(_tokens_for(kind))


def list_devices() -> List[dict]:
    """WebUI 用：已注册设备一览。token 只回前 12 位，够定位又不必全量外泄。"""
    with get_session() as s:
        rows = s.execute(select(DeviceToken).order_by(DeviceToken.created_at)).scalars().all()
        return [{
            "token_prefix": r.token[:12],
            "label": r.label,
            "created_at": r.created_at,
            "last_seen": r.last_seen,
            "notif_captcha": r.notif_captcha,
            "notif_special": r.notif_special,
        } for r in rows]


def _remove_tokens(tokens: List[str]) -> None:
    if not tokens:
        return
    with get_session() as s:
        for t in tokens:
            row = s.get(DeviceToken, t)
            if row:
                s.delete(row)
        s.commit()


# ── 发送 ──────────────────────────────────────────────────────────
def _send(title: str, body: str, data: dict, kind: str) -> int:
    """给开启了该类通知的设备发**纯 data** 高优先级消息，由 app 自行弹通知并路由。
    kind: 'captcha' | 'special'。返回成功数。失效 token 自动清理。"""
    if not _enabled:
        return 0
    tokens = _tokens_for(kind)
    if not tokens:
        return 0
    from firebase_admin import messaging

    payload = {"title": title, "body": body}
    payload.update({k: str(v) for k, v in data.items()})
    message = messaging.MulticastMessage(
        tokens=tokens,
        data=payload,
        android=messaging.AndroidConfig(priority="high"),
    )
    try:
        resp = messaging.send_each_for_multicast(message)
    except Exception:
        return 0

    invalid = []
    for token, result in zip(tokens, resp.responses):
        if not result.success:
            err = getattr(result, "exception", None)
            name = type(err).__name__ if err else ""
            if "Unregistered" in name or "InvalidArgument" in name or "NotFound" in name:
                invalid.append(token)
    _remove_tokens(invalid)
    return resp.success_count


def notify_captcha(captcha_url: Optional[str] = None) -> int:
    """抓取触发验证码时调用：推送「需要验证」通知。"""
    return _send(
        title="微博验证码",
        body="抓取被拦截，点击在 app 内完成验证以继续",
        data={"type": "captcha", "url": captcha_url or ""},
        kind="captcha",
    )


def notify_new_posts(user_name: str, count: int, user_id: str = "") -> int:
    """特别关注用户有新微博时调用：每人每轮聚合一条。"""
    name = user_name or "特别关注用户"
    return _send(
        title="特别关注更新",
        body=f"{name} 发布了 {count} 条新微博",
        data={"type": "new_posts", "user_id": user_id or "", "user_name": name, "count": count},
        kind="special",
    )


def notify_test() -> int:
    """WebUI 的「测试推送」。

    刻意复用 type=new_posts：app 的 onMessageReceived 只认 captcha / new_posts
    两种类型，其它类型会被静默丢弃。若在这里用自定义的 type=test，已经装在
    手机上的版本收不到，测试就失去意义了。user_id 留空，app 点开只是打开首页。
    """
    return _send(
        title="WeiboX 测试推送",
        body="收到这条通知说明 FCM 配置正常",
        data={"type": "new_posts", "user_id": "", "user_name": "测试", "count": 1},
        kind="special",
    )
