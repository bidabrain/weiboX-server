"""访客 session 引导：用无头浏览器复刻 app 的 WebView incarnate 流程。

加载 m.weibo.cn，等页面 JS 自动跑完 genvisitor → incarnate，
拿到对 m.weibo.cn 有效的访客 cookie（SUB / _T_WM 等），导出为 cookie 字符串。
结果缓存在内存，过期或失效时再刷新。
"""
from __future__ import annotations

import asyncio
import time
from typing import Optional

from .browser import browser_pool

MOBILE_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
)
# 访客 cookie 有效期偏短，缓存 30 分钟后强制刷新
CACHE_TTL = 30 * 60


class VisitorSession:
    def __init__(self):
        self._cookie = ""
        self._ts = 0
        self._lock = asyncio.Lock()

    def cached(self) -> str:
        if self._cookie and (time.time() - self._ts) < CACHE_TTL:
            return self._cookie
        return ""

    async def get(self, force: bool = False) -> str:
        if not force:
            c = self.cached()
            if c:
                return c
        async with self._lock:
            if not force:
                c = self.cached()
                if c:
                    return c
            self._cookie = await self._bootstrap()
            self._ts = time.time()
            return self._cookie

    async def _bootstrap(self) -> str:
        browser = await browser_pool.browser()
        context = await browser.new_context(user_agent=MOBILE_UA, locale="zh-CN")
        try:
            page = await context.new_page()
            await page.goto("https://m.weibo.cn/", wait_until="networkidle", timeout=30000)
            # incarnate 后 SUB 才会出现；轮询等待
            for _ in range(10):
                cookies = await context.cookies()
                names = {c["name"] for c in cookies}
                if "SUB" in names or "_T_WM" in names:
                    if "SUB" in names:
                        break
                await asyncio.sleep(0.8)
            cookies = await context.cookies()
            return "; ".join(f"{c['name']}={c['value']}" for c in cookies)
        finally:
            await context.close()


visitor_session = VisitorSession()
