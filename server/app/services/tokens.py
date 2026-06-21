"""对外 API 令牌的管理与校验（数据库存储，WebUI 可增删）。"""
from __future__ import annotations

import hmac
import os
import time
from typing import List, Optional

from sqlalchemy import select

from ..db import get_session
from ..models import ApiToken


def _now_ms() -> int:
    return int(time.time() * 1000)


def list_tokens() -> List[dict]:
    with get_session() as s:
        rows = s.execute(select(ApiToken).order_by(ApiToken.created_at)).scalars().all()
        return [{
            "id": t.id,
            "token": t.token,
            "label": t.label,
            "created_at": t.created_at,
            "last_used_at": t.last_used_at,
        } for t in rows]


def create_token(label: str = "") -> dict:
    tid = os.urandom(5).hex()
    secret = os.urandom(24).hex()
    with get_session() as s:
        row = ApiToken(id=tid, token=secret, label=label or "未命名", created_at=_now_ms())
        s.add(row)
        s.commit()
    return {"id": tid, "token": secret, "label": label or "未命名", "created_at": _now_ms()}


def delete_token(tid: str) -> None:
    with get_session() as s:
        row = s.get(ApiToken, tid)
        if row:
            s.delete(row)
            s.commit()


def is_valid_token(value: str) -> bool:
    """校验明文令牌是否存在。高熵随机令牌，等值索引查找即可。"""
    if not value:
        return False
    with get_session() as s:
        row = s.execute(select(ApiToken).where(ApiToken.token == value)).scalar_one_or_none()
        if row is None:
            return False
        # 常数时间再比对一次，规避任何理论上的旁路
        if not hmac.compare_digest(row.token, value):
            return False
        row.last_used_at = _now_ms()
        s.commit()
        return True
