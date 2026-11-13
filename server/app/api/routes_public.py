"""统一浏览 API（/api/v1）。

WebUI（session cookie）与第三方客户端（Bearer Token）共用这一套。
包含：缓存数据（时间线/关注列表）、实时数据（主页/微博/关注列表/评论/搜索）、
关注管理、图片代理。管理类（设置/cookie/webdav/验证码）见 /admin/api。
"""
from __future__ import annotations

import time
from urllib.parse import parse_qs, urlparse

import httpx
from fastapi import APIRouter, Body, Depends, HTTPException, Query, Request, Response
from fastapi.responses import StreamingResponse

from ..services import live, push, repo
from ..services.scraper import scraper
from ..weibo.client import CaptchaRequired, SessionInvalid, WeiboError
from .auth import require_session_or_token

router = APIRouter(prefix="/api/v1", dependencies=[Depends(require_session_or_token)])


async def _guard(awaitable):
    """统一翻译微博侧异常为 HTTP 错误。"""
    try:
        return await awaitable
    except CaptchaRequired as e:
        raise HTTPException(status_code=409, detail=f"触发验证码：{e.captcha_url}")
    except (SessionInvalid, WeiboError) as e:
        raise HTTPException(status_code=502, detail=str(e))


# ── 状态 ──────────────────────────────────────────────────────────
@router.get("/status")
def status():
    return scraper.status.snapshot()


# ── FCM 设备注册 ──────────────────────────────────────────────────
@router.post("/devices")
def register_device(payload: dict = Body(...)):
    token = str(payload.get("token", "")).strip()
    if not token:
        raise HTTPException(status_code=400, detail="缺少 token")
    # 通知开关缺省视为开启（兼容旧版 app）
    push.register_device(
        token,
        str(payload.get("label", "")).strip(),
        bool(payload.get("notif_captcha", True)),
        bool(payload.get("notif_special", True)),
    )
    return {"ok": True, "push_enabled": push.enabled()}


@router.delete("/devices/{token}")
def unregister_device(token: str):
    push.unregister_device(token)
    return {"ok": True}


# ── 缓存数据（定时抓取）──────────────────────────────────────────
@router.get("/timeline")
def timeline(limit: int = Query(50, ge=1, le=200), offset: int = Query(0, ge=0)):
    return {"posts": repo.get_timeline(limit=limit, offset=offset)}


@router.get("/hot")
def hot(limit: int = Query(50, ge=1, le=200), offset: int = Query(0, ge=0)):
    return {"posts": repo.get_hot_feed(limit=limit, offset=offset)}


@router.get("/special/timeline")
def special_timeline(limit: int = Query(50, ge=1, le=200), offset: int = Query(0, ge=0)):
    return {"posts": repo.get_special_timeline(limit=limit, offset=offset)}


@router.get("/special/users")
def special_users():
    return {"users": repo.list_special_users()}


@router.get("/users")
def users():
    items = repo.list_users()
    for u in items:
        u["followed"] = True
    return {"users": items}


# ── 关注管理 ──────────────────────────────────────────────────────
@router.post("/users")
async def follow(payload: dict = Body(...)):
    uid = str(payload.get("id", "")).strip()
    if not uid:
        raise HTTPException(status_code=400, detail="缺少 id")
    data = payload
    if not payload.get("screen_name"):
        try:
            data = await live.profile(uid)
        except (CaptchaRequired, WeiboError, SessionInvalid):
            data = {"id": uid}
    repo.add_user(data)
    scraper.trigger_now()
    return {"ok": True, "user": data}


@router.delete("/users/{user_id}")
def unfollow(user_id: str):
    repo.remove_user(user_id)
    return {"ok": True}


# ── 特别关注（关注的超集）────────────────────────────────────────
@router.post("/users/{user_id}/special")
async def add_special(user_id: str):
    # 特别关注隐含关注：未关注则先加入关注列表（顺带拉取资料）
    if not repo.is_followed(user_id):
        try:
            data = await live.profile(user_id)
        except (CaptchaRequired, WeiboError, SessionInvalid):
            data = {"id": user_id}
        repo.add_user(data)
        scraper.trigger_now()
    repo.set_special(user_id, True)
    return {"ok": True, "special": True}


@router.delete("/users/{user_id}/special")
def remove_special(user_id: str):
    repo.set_special(user_id, False)
    return {"ok": True, "special": False}


# ── 实时数据（点开才取）──────────────────────────────────────────
@router.get("/users/{user_id}")
async def user_profile(user_id: str):
    data = await _guard(live.profile(user_id))
    data["followed"] = repo.is_followed(user_id)
    data["special"] = repo.is_special(user_id)
    return data


@router.get("/users/{user_id}/posts")
async def user_posts(user_id: str, page: int = Query(1, ge=1),
                     count: int = Query(20, ge=1, le=50)):
    posts = await _guard(live.user_posts(user_id, page=page, count=count))
    return {"posts": posts}


@router.get("/users/{user_id}/following")
async def user_following(user_id: str, page: int = Query(2, ge=1)):
    users_list = await _guard(live.following(user_id, page=page))
    followed = {u["id"] for u in repo.list_users()}
    special = repo.special_user_ids()
    for u in users_list:
        u["followed"] = u["id"] in followed
        u["special"] = u["id"] in special
    return {"users": users_list}


@router.get("/posts/{mid}/comments")
async def post_comments(mid: str, max_id: str = Query(None), page: int = Query(1, ge=1)):
    return await _guard(live.comments(mid, max_id=max_id, page=page))


@router.get("/search")
async def search_users(q: str = Query(...), page: int = Query(1, ge=1)):
    results = await _guard(live.search(q, page))
    followed = {u["id"] for u in repo.list_users()}
    special = repo.special_user_ids()
    for r in results:
        r["followed"] = r["id"] in followed
        r["special"] = r["id"] in special
    return {"users": results}


# ── 图片代理（绕过 sinaimg 防盗链）────────────────────────────────
ALLOWED_IMG_HOSTS = ("sinaimg.cn", "weibo.com", "weibocdn.com", "weibo.cn")
_IMG_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
)


def _host_allowed(url: str) -> bool:
    host = urlparse(url).hostname or ""
    return any(host == h or host.endswith("." + h) for h in ALLOWED_IMG_HOSTS)


@router.get("/img")
async def img_proxy(u: str = Query(...)):
    # 不自动跟随重定向；手动逐跳校验 host，防止 302 到内网/元数据 (SSRF)
    if not _host_allowed(u):
        raise HTTPException(status_code=400, detail="不允许的图片域名")
    headers = {"User-Agent": _IMG_UA, "Referer": "https://weibo.com/"}
    try:
        async with httpx.AsyncClient(timeout=20.0, follow_redirects=False) as c:
            current = u
            for _ in range(4):  # 最多跟 3 次重定向，每跳都校验
                r = await c.get(current, headers=headers)
                if r.status_code in (301, 302, 303, 307, 308):
                    loc = r.headers.get("location", "")
                    if not loc:
                        break
                    nxt = str(httpx.URL(current).join(loc))
                    if not _host_allowed(nxt):
                        raise HTTPException(status_code=400, detail="重定向目标不被允许")
                    current = nxt
                    continue
                break
    except httpx.HTTPError as e:
        raise HTTPException(status_code=502, detail=f"图片获取失败：{e}")
    if r.status_code != 200:
        raise HTTPException(status_code=502, detail=f"图片获取失败 {r.status_code}")
    return Response(
        content=r.content,
        media_type=r.headers.get("content-type", "image/jpeg"),
        headers={"Cache-Control": "public, max-age=86400"},
    )


# ── 视频代理（防盗链 + 签名过期自动续 + Range 拖动）──────────────────
# 微博视频地址带 Expires/ssig 签名，几小时后失效；缓存里的帖子很容易拿到过期地址。
# 这里按所属帖子 mid 重新拉一次详情换新地址，并在内存里记住，避免每次都打微博。
_VIDEO_URL_CACHE: dict = {}          # mid -> 最近一次拿到的有效地址
_VIDEO_PASS_HEADERS = ("content-type", "content-length", "content-range", "accept-ranges",
                       "last-modified", "etag")


def _url_expired(url: str, margin: int = 60) -> bool:
    qs = parse_qs(urlparse(url).query)
    exp = (qs.get("Expires") or qs.get("expires") or [""])[0]
    try:
        return int(exp) < time.time() + margin
    except ValueError:
        return False  # 没有过期参数就当它有效，真失效了会按 403 再刷新


async def _open_video(url: str, range_header: str):
    """打开上游视频流，手动跟随并校验每一跳重定向。返回 (client, response)。"""
    headers = {"User-Agent": _IMG_UA, "Referer": "https://m.weibo.cn/"}
    if range_header:
        headers["Range"] = range_header
    client = httpx.AsyncClient(timeout=httpx.Timeout(30.0, connect=15.0), follow_redirects=False)
    try:
        current = url
        for _ in range(4):
            r = await client.send(client.build_request("GET", current, headers=headers), stream=True)
            if r.status_code in (301, 302, 303, 307, 308):
                loc = r.headers.get("location", "")
                await r.aclose()
                nxt = str(httpx.URL(current).join(loc)) if loc else ""
                if not nxt or not _host_allowed(nxt):
                    raise HTTPException(status_code=400, detail="重定向目标不被允许")
                current = nxt
                continue
            return client, r
        raise HTTPException(status_code=502, detail="视频重定向次数过多")
    except BaseException:
        await client.aclose()
        raise


@router.get("/video")
async def video_proxy(request: Request, mid: str = Query(...), u: str = Query("")):
    url = _VIDEO_URL_CACHE.get(mid) or u
    if url and not _host_allowed(url):
        raise HTTPException(status_code=400, detail="不允许的视频域名")
    range_header = request.headers.get("range", "")

    refreshed = False
    if not url or _url_expired(url):
        url = await _guard(live.fresh_video_url(mid))
        refreshed = True
    for attempt in range(2):
        if not url or not _host_allowed(url):
            raise HTTPException(status_code=404, detail="该微博没有可播放的视频")
        try:
            client, r = await _open_video(url, range_header)
        except httpx.HTTPError as e:
            raise HTTPException(status_code=502, detail=f"视频获取失败：{e}")
        if r.status_code in (200, 206, 416):  # 416=Range 越界，属正常应答，原样透传
            if len(_VIDEO_URL_CACHE) > 2000:
                _VIDEO_URL_CACHE.clear()
            _VIDEO_URL_CACHE[mid] = url
            break
        await r.aclose()
        await client.aclose()
        if refreshed or attempt == 1:
            raise HTTPException(status_code=502, detail=f"视频获取失败 {r.status_code}")
        # 地址可能已失效（403/404 等），换新地址再试一次
        _VIDEO_URL_CACHE.pop(mid, None)
        url = await _guard(live.fresh_video_url(mid))
        refreshed = True

    async def _body():
        # 放在生成器 finally 里关：播放器中途断开（拖动/关掉）时也能释放上游连接
        try:
            async for chunk in r.aiter_raw():
                yield chunk
        finally:
            await r.aclose()
            await client.aclose()

    headers = {k: v for k, v in r.headers.items() if k.lower() in _VIDEO_PASS_HEADERS}
    headers.setdefault("accept-ranges", "bytes")
    headers["cache-control"] = "private, max-age=3600"
    return StreamingResponse(
        _body(), status_code=r.status_code, headers=headers,
        media_type=r.headers.get("content-type", "video/mp4"),
    )
