"""实时数据：点开才需要的内容，当场代理微博返回（不入库）。

用户主页详情 / 该用户微博 / 该用户的关注列表 / 评论 / 搜索。
统一用 auth_cookie.new_client()（配置 cookie 或访客 session）。
"""
from __future__ import annotations

from typing import List, Optional

from ..weibo.auth_cookie import new_client


async def profile(uid: str) -> dict:
    client = await new_client()
    try:
        return await client.get_user_info(uid)
    finally:
        await client.aclose()


async def user_posts(uid: str, page: int = 1, count: int = 20) -> List[dict]:
    client = await new_client()
    try:
        return await client.get_user_posts(uid, page=page, count=count)
    finally:
        await client.aclose()


async def following(uid: str, page: int = 2) -> List[dict]:
    client = await new_client()
    try:
        return await client.get_following_list(uid, page=page)
    finally:
        await client.aclose()


async def comments(mid: str, max_id: Optional[str] = None, page: int = 1):
    client = await new_client()
    try:
        items, next_max_id = await client.get_comments(mid, max_id=max_id, page=page)
        return {"comments": items, "next_max_id": next_max_id}
    finally:
        await client.aclose()


async def search(q: str, page: int = 1) -> List[dict]:
    client = await new_client()
    try:
        return await client.search_users(q, page)
    finally:
        await client.aclose()
