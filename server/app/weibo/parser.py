"""微博接口/HTML 解析。逻辑移植自 app 的 WeiboApi.kt。

返回普通 dict，键名与 app 模型对齐（screen_name / avatar_url 等），
方便直接落库与 WebDAV 互通。
"""
from __future__ import annotations

import html as html_module
import re
from datetime import datetime
from typing import List, Optional

from bs4 import BeautifulSoup

# created_at 形如 "Wed Jun 18 10:20:30 +0800 2025"
_WEIBO_DATE_FMT = "%a %b %d %H:%M:%S %z %Y"
_TAG_RE = re.compile(r"<[^>]+>")


def parse_weibo_time(s: str) -> int:
    """微博时间字符串 → 毫秒时间戳，失败返回 0。"""
    if not s:
        return 0
    try:
        return int(datetime.strptime(s, _WEIBO_DATE_FMT).timestamp() * 1000)
    except (ValueError, TypeError):
        return 0


def strip_html(s: str) -> str:
    """等价 app 的 stripHtml：去标签 + 反转义，得到纯文本。"""
    if not s:
        return ""
    # <br> 转换行，保留可读性
    s = re.sub(r"<br\s*/?>", "\n", s, flags=re.IGNORECASE)
    s = _TAG_RE.sub("", s)
    return html_module.unescape(s).strip()


def parse_user_json(u: dict) -> dict:
    return {
        "id": str(u.get("id", "")),
        "screen_name": u.get("screen_name", "") or "",
        "description": u.get("description", "") or "",
        "avatar_url": u.get("avatar_hd") or u.get("profile_image_url") or "",
        "cover_url": u.get("cover_image_phone", "") or "",
        "followers_count": str(u.get("followers_count", "") or ""),
        "follow_count": _to_int(u.get("follow_count")),
        "statuses_count": _to_int(u.get("statuses_count")),
        "verified": bool(u.get("verified", False)),
        "verified_reason": u.get("verified_reason", "") or "",
    }


def parse_user_info(data: dict) -> dict:
    """getIndex(100505{uid}) → 用户信息。data 为已解析的 JSON。"""
    info = (data.get("data") or {}).get("userInfo")
    if not info:
        raise ValueError("无用户信息")
    return parse_user_json(info)


def parse_pics(arr) -> List[str]:
    if not arr:
        return []
    pics = []
    for pic in arr:
        url = (pic.get("large") or {}).get("url") or pic.get("url") or ""
        if url:
            pics.append(url)
    return pics


# 视频地址按清晰度从高到低挑；不同接口/版本字段名不一，都列上
_VIDEO_URL_KEYS = (
    "mp4_720p_mp4", "mp4_hd_mp4", "mp4_hd_url", "hevc_mp4_hd",
    "stream_url_hd", "mp4_sd_url", "stream_url", "mp4_ld_mp4",
)


def _pick_video_url(*sources) -> str:
    for key in _VIDEO_URL_KEYS:
        for src in sources:
            if isinstance(src, dict):
                url = src.get(key)
                if isinstance(url, str) and url.startswith("http"):
                    return url
    return ""


def _video_from_page_info(pi: dict, mid: str) -> Optional[dict]:
    if not isinstance(pi, dict) or pi.get("type") != "video":
        return None
    media = pi.get("media_info") or {}
    url = _pick_video_url(pi.get("urls"), media)
    if not url:
        return None
    cover = (pi.get("page_pic") or {}).get("url") or ""
    try:
        duration = int(float(media.get("duration") or 0))
    except (TypeError, ValueError):
        duration = 0
    return {"mid": mid, "url": url, "cover": cover, "duration": duration}


def parse_video(mblog: dict) -> Optional[dict]:
    """帖子自带的视频 → {mid, url, cover, duration}，没有则 None。

    视频地址带签名、会过期，所以同时记下所属帖子 mid，代理播放时过期可按 mid 重新取。
    普通视频帖在 page_info（type=video）；图文混排帖在 mix_media_info.items。
    """
    mid = str(mblog.get("id", ""))
    v = _video_from_page_info(mblog.get("page_info"), mid)
    if v:
        return v
    for item in (mblog.get("mix_media_info") or {}).get("items") or []:
        if item.get("type") == "video":
            data = item.get("data") or {}
            v = _video_from_page_info(data, mid) if data.get("type") == "video" else None
            if not v:
                media = data.get("media_info") or {}
                url = _pick_video_url(data.get("urls"), media)
                if url:
                    v = {
                        "mid": mid, "url": url,
                        "cover": (data.get("page_pic") or {}).get("url") or "",
                        "duration": _to_int(media.get("duration")),
                    }
            if v:
                return v
    return None


def parse_post(mblog: dict) -> dict:
    user = mblog.get("user") or {}
    retweeted = mblog.get("retweeted_status")
    created_at = mblog.get("created_at", "") or ""
    return {
        "id": str(mblog.get("id", "")),
        "user_id": str(user.get("id", "")),
        "user_name": user.get("screen_name", "") or "",
        "user_avatar": user.get("avatar_hd") or user.get("profile_image_url") or "",
        "text": strip_html(mblog.get("text", "") or ""),
        "pics": parse_pics(mblog.get("pics")),
        "video": parse_video(mblog),
        "created_at": created_at,
        "created_at_ts": parse_weibo_time(created_at),
        "likes_count": _to_int(mblog.get("attitudes_count")),
        "comments_count": _to_int(mblog.get("comments_count")),
        "reposts_count": _to_int(mblog.get("reposts_count")),
        "source": strip_html(mblog.get("source", "") or ""),
        "is_retweet": retweeted is not None,
        # 微博主页置顶帖标记 isTop=1：发布时间往往很旧，需单独处理以免被反复 trim/误推
        "is_top": bool(mblog.get("isTop", 0)),
        "retweet": parse_post(retweeted) if retweeted else None,
    }


def parse_posts(data: dict) -> List[dict]:
    """getIndex(230413{uid}) → 微博列表。data 为已解析 JSON。"""
    cards = (data.get("data") or {}).get("cards") or []
    posts = []
    for card in cards:
        if card.get("card_type") == 9:
            mblog = card.get("mblog")
            if mblog:
                try:
                    posts.append(parse_post(mblog))
                except Exception:
                    continue
    return posts


def parse_hot_posts(data: dict) -> List[dict]:
    """getIndex(102803 等热门容器) → 微博列表，保留返回顺序（即热度顺序）。

    热门流的帖子可能直接是 card_type==9，也可能嵌在 card_type==11 的
    card_group 里，这里两种都展开。按出现顺序去重。
    """
    cards = (data.get("data") or {}).get("cards") or []
    posts = []
    seen = set()

    def _take(card: dict) -> None:
        mblog = card.get("mblog")
        if not mblog:
            return
        try:
            p = parse_post(mblog)
        except Exception:
            return
        if p["id"] and p["id"] not in seen:
            seen.add(p["id"])
            posts.append(p)

    for card in cards:
        if card.get("card_type") == 9:
            _take(card)
        for item in card.get("card_group") or []:
            if item.get("card_type") == 9:
                _take(item)
    return posts


def parse_following_list(data: dict) -> List[dict]:
    """getIndex(231051_-_followers_-_{uid}) → 关注的人列表。"""
    if data.get("ok") != 1:
        return []
    cards = (data.get("data") or {}).get("cards") or []
    users = []
    for card in cards:
        for item in card.get("card_group") or []:
            if item.get("card_type") == 10 and item.get("user"):
                users.append(parse_user_json(item["user"]))
    return users


def parse_user_search_api(data: dict) -> List[dict]:
    """cookie 模式：getIndex(100103type=3&q=) → 用户搜索结果。"""
    cards = (data.get("data") or {}).get("cards") or []
    users = []
    for card in cards:
        if card.get("user"):
            users.append(parse_user_json(card["user"]))
        for item in card.get("card_group") or []:
            if item.get("user"):
                users.append(parse_user_json(item["user"]))
    seen = set()
    result = []
    for u in users:
        if u["id"] and u["id"] not in seen:
            seen.add(u["id"])
            result.append(u)
    return result


def parse_user_search_html(html: str) -> List[dict]:
    """访客模式：s.weibo.com/user 搜索结果 HTML 解析。"""
    soup = BeautifulSoup(html, "lxml")
    users = []
    for card in soup.select("div.card.card-user-b"):
        a = card.select_one("div.avator a")
        if not a:
            continue
        href = a.get("href", "")
        uid = href.rsplit("/", 1)[-1]
        if not uid or not uid.isdigit():
            continue
        img = card.select_one("div.avator img")
        avatar = img.get("src", "") if img else ""
        if avatar.startswith("//"):
            avatar = "https:" + avatar
        name_el = card.select_one("a.name")
        screen_name = name_el.get_text(strip=True) if name_el else ""
        paras = card.select("div.info p")
        description = paras[0].get_text(strip=True) if paras else ""
        followers_el = card.select_one("span.s-nobr")
        followers = ""
        if followers_el:
            followers = followers_el.get_text(strip=True).replace("粉丝：", "")
        verified = card.select_one("span.woo-icon-wrap") is not None
        users.append({
            "id": uid,
            "screen_name": screen_name,
            "description": description,
            "avatar_url": avatar,
            "cover_url": "",
            "followers_count": followers,
            "follow_count": 0,
            "statuses_count": 0,
            "verified": verified,
            "verified_reason": "",
        })
    return users


def parse_comments(data: dict, has_cookie: bool):
    """评论解析。返回 (comments, next_max_id)。"""
    d = data.get("data") or {}
    arr = d.get("data") or []
    next_max_id = None
    if has_cookie:
        mid = str(d.get("max_id", ""))
        if mid and mid != "0":
            next_max_id = mid
    comments = []
    for c in arr:
        try:
            user = c.get("user") or {}
            comments.append({
                "id": str(c.get("id", "")),
                "text": strip_html(c.get("text", "") or ""),
                "created_at": c.get("created_at", "") or "",
                "created_at_ts": parse_weibo_time(c.get("created_at", "") or ""),
                "user_name": user.get("screen_name", "") or "",
                "user_avatar": user.get("avatar_hd") or user.get("profile_image_url") or "",
                "like_count": _to_int(c.get("like_count")),
            })
        except Exception:
            continue
    return comments, next_max_id


def _to_int(v) -> int:
    try:
        return int(v)
    except (ValueError, TypeError):
        return 0
