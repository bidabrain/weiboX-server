"""WebDAV 备份/恢复。格式与 app 的 WebDavService.kt 完全一致：
固定文件名 weibox_backup.json，version=2，只含 cookie + 关注列表。"""
from __future__ import annotations

import json
from datetime import datetime
from typing import List, Tuple

import httpx

BACKUP_FILENAME = "weibox_backup.json"


def _backup_url(base_url: str) -> str:
    return f"{base_url.rstrip('/')}/{BACKUP_FILENAME}"


def build_backup_json(users: List[dict], cookie: str) -> str:
    arr = []
    for u in users:
        arr.append({
            "id": str(u.get("id", "")),
            "screen_name": u.get("screen_name", ""),
            "description": u.get("description", ""),
            "avatar_url": u.get("avatar_url", ""),
            "cover_url": u.get("cover_url", ""),
            "followers_count": str(u.get("followers_count", "")),
            "follow_count": int(u.get("follow_count", 0) or 0),
            "statuses_count": int(u.get("statuses_count", 0) or 0),
            "verified": bool(u.get("verified", False)),
            "verified_reason": u.get("verified_reason", ""),
        })
    payload = {
        "version": 2,
        "backup_time": datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
        "cookie": cookie,
        "users": arr,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


def parse_backup_json(text: str) -> Tuple[List[dict], str]:
    root = json.loads(text)
    cookie = root.get("cookie", "") or ""
    users = []
    for u in root.get("users", []):
        users.append({
            "id": str(u.get("id", "")),
            "screen_name": u.get("screen_name", ""),
            "description": u.get("description", ""),
            "avatar_url": u.get("avatar_url", ""),
            "cover_url": u.get("cover_url", ""),
            "followers_count": str(u.get("followers_count", "")),
            "follow_count": int(u.get("follow_count", 0) or 0),
            "statuses_count": int(u.get("statuses_count", 0) or 0),
            "verified": bool(u.get("verified", False)),
            "verified_reason": u.get("verified_reason", ""),
        })
    return users, cookie


def backup(base_url: str, username: str, password: str, users: List[dict], cookie: str) -> None:
    body = build_backup_json(users, cookie)
    with httpx.Client(timeout=30.0) as c:
        resp = c.put(
            _backup_url(base_url),
            content=body.encode("utf-8"),
            headers={"Content-Type": "application/json; charset=utf-8"},
            auth=(username, password),
        )
    if resp.status_code not in (200, 201, 204):
        raise RuntimeError(f"WebDAV 上传失败 (HTTP {resp.status_code})")


def restore(base_url: str, username: str, password: str) -> Tuple[List[dict], str]:
    with httpx.Client(timeout=30.0, follow_redirects=True) as c:
        resp = c.get(_backup_url(base_url), auth=(username, password))
    if resp.status_code != 200:
        raise RuntimeError(f"WebDAV 下载失败 (HTTP {resp.status_code})")
    return parse_backup_json(resp.text)
