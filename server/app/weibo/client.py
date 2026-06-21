"""微博 HTTP 客户端（httpx 异步）。移植自 app 的 WeiboApi.kt。

- 用 cookie 字符串初始化，请求间自动捕获服务器下发的新 token（含 XSRF-TOKEN）。
- 每次请求注入 m.weibo.cn 所需的请求头与 X-XSRF-TOKEN。
- 命中验证码限制时抛 CaptchaRequired，由上层用 Playwright 处理。
- 访客搜索走 s.weibo.com（genvisitor2 取临时 SUB/SUBP）。
"""
from __future__ import annotations

import random
import urllib.parse
from http.cookies import SimpleCookie
from typing import List, Optional, Tuple

import httpx

from . import parser

USER_AGENTS = [
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
    "Mozilla/5.0 (Linux; Android 14; SM-S9180) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.6367.113 Mobile Safari/537.36",
]
DESKTOP_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)


class CaptchaRequired(Exception):
    def __init__(self, captcha_url: str):
        super().__init__("触发验证码限制")
        self.captcha_url = captcha_url


class SessionInvalid(Exception):
    """会话失效（ok=-100 / 需登录）。无 cookie 时应引导访客 session 或要求配置 cookie。"""
    pass


class WeiboError(Exception):
    pass


class WeiboClient:
    def __init__(self, cookie_string: str = ""):
        self.cookie_string = cookie_string or ""
        self.has_cookie = bool(self.cookie_string.strip())

        cookies = httpx.Cookies()
        if self.has_cookie:
            for part in self.cookie_string.split(";"):
                if "=" in part:
                    name, value = part.split("=", 1)
                    name, value = name.strip(), value.strip()
                    if name:
                        cookies.set(name, value, domain=".weibo.cn")
        self._client = httpx.AsyncClient(
            cookies=cookies,
            timeout=httpx.Timeout(20.0, connect=15.0),
            follow_redirects=True,
        )

    async def aclose(self):
        await self._client.aclose()

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        await self.aclose()

    # ── cookie 同步（验证码解完后用）────────────────────────────────
    def export_cookie_string(self) -> str:
        return "; ".join(f"{c.name}={c.value}" for c in self._client.cookies.jar)

    def merge_cookies(self, cookie_string: str) -> None:
        for part in cookie_string.split(";"):
            if "=" in part:
                name, value = part.split("=", 1)
                name, value = name.strip(), value.strip()
                if name:
                    self._client.cookies.set(name, value, domain=".weibo.cn")
        self.has_cookie = True

    def _xsrf(self) -> str:
        for c in self._client.cookies.jar:
            if c.name == "XSRF-TOKEN":
                return c.value or ""
        return ""

    def _headers(self) -> dict:
        h = {
            "User-Agent": random.choice(USER_AGENTS),
            "Accept": "application/json, text/plain, */*",
            "Referer": "https://m.weibo.cn/",
            "MWeibo-Pwa": "1",
            "X-Requested-With": "XMLHttpRequest",
        }
        xsrf = self._xsrf()
        if xsrf:
            h["X-XSRF-TOKEN"] = xsrf
        return h

    async def _get_json(self, url: str) -> dict:
        try:
            resp = await self._client.get(url, headers=self._headers())
        except httpx.HTTPError as e:
            raise WeiboError(f"网络错误：{e}") from e
        if resp.status_code != 200:
            reason = {
                432: "访问受限（432），请检查/更新 Cookie",
                401: "需要登录，请配置 Cookie",
                403: "无访问权限，请检查 Cookie 是否有效",
                429: "请求过于频繁，请稍后再试",
            }.get(resp.status_code, f"网络错误 {resp.status_code}")
            raise WeiboError(reason)
        try:
            return resp.json()
        except ValueError as e:
            raise WeiboError(f"响应非 JSON：{url}") from e

    @staticmethod
    def _check_ok(data: dict) -> None:
        if data.get("ok") == 1:
            return
        url = data.get("url", "") or ""
        if "captcha" in url:
            raise CaptchaRequired(url)
        ok = data.get("ok")
        msg = data.get("msg")
        if ok == -100 or "passport.weibo" in url or "signin" in url:
            raise SessionInvalid("会话失效（ok=-100），请配置有效 Cookie 或启用访客 session")
        raise WeiboError(msg or f"接口错误 ok={ok}")

    # ── 接口 ───────────────────────────────────────────────────────
    async def get_user_info(self, user_id: str) -> dict:
        url = f"https://m.weibo.cn/api/container/getIndex?containerid=100505{user_id}"
        data = await self._get_json(url)
        self._check_ok(data)
        return parser.parse_user_info(data)

    async def get_user_posts(self, user_id: str, page: int = 1, count: int = 20) -> List[dict]:
        url = (
            f"https://m.weibo.cn/api/container/getIndex"
            f"?containerid=230413{user_id}&page={page}&count={count}"
        )
        data = await self._get_json(url)
        if data.get("ok") != 1:
            url_field = data.get("url", "") or ""
            if "captcha" in url_field:
                raise CaptchaRequired(url_field)
            if data.get("ok") == -100 or "passport.weibo" in url_field or "signin" in url_field:
                raise SessionInvalid("会话失效（ok=-100），请配置有效 Cookie 或启用访客 session")
            return []
        return parser.parse_posts(data)

    async def get_following_list(self, user_id: str, page: int = 2) -> List[dict]:
        url = (
            f"https://m.weibo.cn/api/container/getIndex"
            f"?containerid=231051_-_followers_-_{user_id}&page={page}"
        )
        data = await self._get_json(url)
        return parser.parse_following_list(data)

    async def get_comments(self, post_id: str, max_id: Optional[str] = None,
                           page: int = 1) -> Tuple[List[dict], Optional[str]]:
        has_cookie = bool(self._xsrf())
        if has_cookie:
            url = f"https://m.weibo.cn/comments/hotflow?max_id_type=0&mid={post_id}"
            if max_id:
                url += f"&max_id={max_id}"
        else:
            url = f"https://m.weibo.cn/api/comments/show?id={post_id}&page={page}"
        data = await self._get_json(url)
        return parser.parse_comments(data, has_cookie=has_cookie)

    async def search_users(self, query: str, page: int = 1) -> List[dict]:
        if not self.has_cookie:
            return await self._search_users_web(query, page)
        encoded = urllib.parse.quote(query)
        url = (
            f"https://m.weibo.cn/api/container/getIndex"
            f"?containerid=100103type%3D3%26q%3D{encoded}"
            f"&page_type=searchall&page={page}"
        )
        data = await self._get_json(url)
        self._check_ok(data)
        return parser.parse_user_search_api(data)

    # ── 访客搜索（无 cookie）────────────────────────────────────────
    async def _get_visitor_tokens(self) -> Tuple[str, str]:
        url = "https://passport.weibo.com/visitor/genvisitor2?cb=visitor_gray_callback&reg=0"
        async with httpx.AsyncClient(timeout=20.0) as plain:
            resp = await plain.get(url, headers={
                "User-Agent": USER_AGENTS[0],
                "Referer": "https://s.weibo.com/",
            })
        body = resp.text
        inner = body[body.find("(") + 1: body.rfind(")")]
        import json
        data = json.loads(inner)
        if data.get("retcode") != 20000000:
            raise WeiboError("访客 token 返回异常")
        d = data["data"]
        return d["sub"], d["subp"]

    async def _search_users_web(self, query: str, page: int = 1) -> List[dict]:
        sub, subp = await self._get_visitor_tokens()
        encoded = urllib.parse.quote(query)
        page_param = f"&page={page}" if page > 1 else ""
        url = f"https://s.weibo.com/user?q={encoded}&Refer=index{page_param}"
        async with httpx.AsyncClient(timeout=20.0, follow_redirects=True) as plain:
            resp = await plain.get(url, headers={
                "User-Agent": DESKTOP_UA,
                "Referer": "https://weibo.com/",
                "Accept-Language": "zh-CN,zh;q=0.9",
                "Cookie": f"SUB={sub}; SUBP={subp}",
            })
        return parser.parse_user_search_html(resp.text)
