"""关注用户 / 微博的数据库读写。所有对外返回 app 风格的 dict。"""
from __future__ import annotations

import json
import time
from typing import List, Optional

from sqlalchemy import delete, func, select

from ..db import get_session
from ..models import FollowedUser, Post


def now_ms() -> int:
    return int(time.time() * 1000)


# ── 关注用户 ──────────────────────────────────────────────────────
def user_to_dict(u: FollowedUser) -> dict:
    return {
        "id": u.id,
        "screen_name": u.screen_name,
        "description": u.description,
        "avatar_url": u.avatar_url,
        "cover_url": u.cover_url,
        "followers_count": u.followers_count,
        "follow_count": u.follow_count,
        "statuses_count": u.statuses_count,
        "verified": u.verified,
        "verified_reason": u.verified_reason,
        "last_fetched_at": u.last_fetched_at,
    }


def list_users() -> List[dict]:
    with get_session() as s:
        rows = s.execute(select(FollowedUser).order_by(FollowedUser.added_at)).scalars().all()
        return [user_to_dict(u) for u in rows]


def is_followed(user_id: str) -> bool:
    with get_session() as s:
        return s.get(FollowedUser, user_id) is not None


def add_user(data: dict) -> None:
    with get_session() as s:
        existing = s.get(FollowedUser, str(data["id"]))
        if existing:
            # 更新资料但保留抓取状态
            existing.screen_name = data.get("screen_name", existing.screen_name)
            existing.description = data.get("description", existing.description)
            existing.avatar_url = data.get("avatar_url", existing.avatar_url)
            existing.cover_url = data.get("cover_url", existing.cover_url)
            existing.followers_count = data.get("followers_count", existing.followers_count)
            existing.follow_count = data.get("follow_count", existing.follow_count)
            existing.statuses_count = data.get("statuses_count", existing.statuses_count)
            existing.verified = data.get("verified", existing.verified)
            existing.verified_reason = data.get("verified_reason", existing.verified_reason)
        else:
            s.add(FollowedUser(
                id=str(data["id"]),
                screen_name=data.get("screen_name", ""),
                description=data.get("description", ""),
                avatar_url=data.get("avatar_url", ""),
                cover_url=data.get("cover_url", ""),
                followers_count=str(data.get("followers_count", "")),
                follow_count=int(data.get("follow_count", 0) or 0),
                statuses_count=int(data.get("statuses_count", 0) or 0),
                verified=bool(data.get("verified", False)),
                verified_reason=data.get("verified_reason", ""),
                last_fetched_at=0,
                added_at=now_ms(),
            ))
        s.commit()


def remove_user(user_id: str) -> None:
    with get_session() as s:
        u = s.get(FollowedUser, user_id)
        if u:
            s.delete(u)
        s.execute(delete(Post).where(Post.user_id == user_id))
        s.commit()


def replace_all_users(users: List[dict]) -> int:
    """WebDAV 恢复用：合并导入（已存在则更新资料，不删现有）。返回新增数。"""
    added = 0
    for u in users:
        if not is_followed(str(u.get("id", ""))):
            added += 1
        add_user(u)
    return added


def update_last_fetched(user_id: str, ts: int) -> None:
    with get_session() as s:
        u = s.get(FollowedUser, user_id)
        if u:
            u.last_fetched_at = ts
            s.commit()


def backfill_profile(user_id: str, name: str, avatar: str) -> None:
    """关注列表里资料为空时（如仅按 UID 关注），用抓到的微博作者信息补全。"""
    if not name and not avatar:
        return
    with get_session() as s:
        u = s.get(FollowedUser, user_id)
        if u and not u.screen_name:
            u.screen_name = name or u.screen_name
            u.avatar_url = avatar or u.avatar_url
            s.commit()


# ── 抓取调度统计 ──────────────────────────────────────────────────
def post_stats_by_user() -> dict:
    """每用户的 (count, oldest_ts, newest_ts)，用于估算发帖间隔。"""
    with get_session() as s:
        rows = s.execute(
            select(
                Post.user_id,
                func.count(Post.id),
                func.min(Post.created_at_ts),
                func.max(Post.created_at_ts),
            ).group_by(Post.user_id)
        ).all()
    return {r[0]: {"count": r[1], "oldest": r[2] or 0, "newest": r[3] or 0} for r in rows}


# ── 微博 ─────────────────────────────────────────────────────────
def post_to_dict(p: Post) -> dict:
    return {
        "id": p.id,
        "user_id": p.user_id,
        "user_name": p.user_name,
        "user_avatar": p.user_avatar,
        "text": p.text,
        "pics": json.loads(p.pics_json or "[]"),
        "created_at": p.created_at,
        "created_at_ts": p.created_at_ts,
        "likes_count": p.likes_count,
        "comments_count": p.comments_count,
        "reposts_count": p.reposts_count,
        "source": p.source,
        "is_retweet": p.is_retweet,
        "retweet": json.loads(p.retweet_json) if p.retweet_json else None,
    }


def save_posts(posts: List[dict]) -> None:
    if not posts:
        return
    ts = now_ms()
    with get_session() as s:
        for p in posts:
            if not p.get("id"):
                continue
            row = s.get(Post, p["id"])
            payload = dict(
                user_id=p.get("user_id", ""),
                user_name=p.get("user_name", ""),
                user_avatar=p.get("user_avatar", ""),
                text=p.get("text", ""),
                pics_json=json.dumps(p.get("pics", []), ensure_ascii=False),
                created_at=p.get("created_at", ""),
                created_at_ts=p.get("created_at_ts", 0),
                likes_count=p.get("likes_count", 0),
                comments_count=p.get("comments_count", 0),
                reposts_count=p.get("reposts_count", 0),
                source=p.get("source", ""),
                is_retweet=p.get("is_retweet", False),
                retweet_json=json.dumps(p["retweet"], ensure_ascii=False) if p.get("retweet") else "",
                fetched_at=ts,
            )
            if row:
                for k, v in payload.items():
                    setattr(row, k, v)
            else:
                s.add(Post(id=p["id"], **payload))
        s.commit()


def get_timeline(limit: int = 50, offset: int = 0) -> List[dict]:
    with get_session() as s:
        rows = s.execute(
            select(Post).order_by(Post.created_at_ts.desc()).limit(limit).offset(offset)
        ).scalars().all()
        return [post_to_dict(p) for p in rows]


def get_user_posts(user_id: str, limit: int = 50, offset: int = 0) -> List[dict]:
    with get_session() as s:
        rows = s.execute(
            select(Post).where(Post.user_id == user_id)
            .order_by(Post.created_at_ts.desc()).limit(limit).offset(offset)
        ).scalars().all()
        return [post_to_dict(p) for p in rows]


def post_count() -> int:
    with get_session() as s:
        return s.execute(select(func.count(Post.id))).scalar() or 0


def trim_posts(retention_days: int, max_posts: int) -> None:
    with get_session() as s:
        if retention_days > 0:
            cutoff = now_ms() - retention_days * 24 * 3600 * 1000
            s.execute(delete(Post).where(Post.created_at_ts < cutoff, Post.created_at_ts > 0))
            s.commit()
        if max_posts > 0:
            count = s.execute(select(func.count(Post.id))).scalar() or 0
            if count > max_posts:
                excess = count - max_posts
                old_ids = s.execute(
                    select(Post.id).order_by(Post.created_at_ts.asc()).limit(excess)
                ).scalars().all()
                if old_ids:
                    s.execute(delete(Post).where(Post.id.in_(old_ids)))
                    s.commit()
