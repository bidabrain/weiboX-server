"""验证码交互协调器：Playwright 无头浏览器 + WebUI 远程操作。

抓取命中限流时，上层调用 solve(captcha_url, cookie)：
 1. 用当前 cookie 拉起一个 Chromium 上下文，打开验证码页面；
 2. 标记 pending，WebUI 通过 WebSocket 拿到截图流并把点击/滑动转发回来；
 3. 用户解完（点完成或页面跳离验证码）后，导出更新后的 cookie 返回上层，
    上层 merge 回抓取客户端继续。
同一时刻只处理一个验证码（_lock 串行）。
"""
from __future__ import annotations

import asyncio
from typing import List, Optional

from .browser import browser_pool

MOBILE_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
)
VIEWPORT = {"width": 390, "height": 760}


def _cookies_from_string(cookie_string: str) -> List[dict]:
    out = []
    for part in (cookie_string or "").split(";"):
        if "=" in part:
            name, value = part.split("=", 1)
            name, value = name.strip(), value.strip()
            if name:
                out.append({
                    "name": name, "value": value,
                    "domain": ".weibo.cn", "path": "/",
                })
    return out


class CaptchaManager:
    def __init__(self):
        self._context = None
        self._page = None
        self._resolved: Optional[asyncio.Future] = None
        self._lock = asyncio.Lock()

        self.pending: bool = False
        self.captcha_url: Optional[str] = None
        self.last_message: str = ""

    # ── 主流程 ─────────────────────────────────────────────────────
    async def solve(self, captcha_url: str, cookie_string: str, timeout: int = 600) -> Optional[str]:
        """打开验证码页等待人工处理，返回更新后的 cookie 字符串；超时返回 None。"""
        async with self._lock:
            browser = await browser_pool.browser()
            self._context = await browser.new_context(
                user_agent=MOBILE_UA, viewport=VIEWPORT, locale="zh-CN",
            )
            await self._context.add_cookies(_cookies_from_string(cookie_string))
            self._page = await self._context.new_page()
            try:
                await self._page.goto(captcha_url, wait_until="domcontentloaded", timeout=30000)
            except Exception as e:
                self.last_message = f"打开验证码页失败：{e}"

            self.pending = True
            self.captcha_url = captcha_url
            self._resolved = asyncio.get_event_loop().create_future()

            try:
                await asyncio.wait_for(self._resolved, timeout=timeout)
                solved = True
            except asyncio.TimeoutError:
                solved = False
                self.last_message = "验证码等待超时"

            cookie_out = None
            if solved:
                cookies = await self._context.cookies()
                cookie_out = "; ".join(f"{c['name']}={c['value']}" for c in cookies)

            await self._teardown_session()
            return cookie_out

    async def _teardown_session(self):
        self.pending = False
        self.captcha_url = None
        try:
            if self._page:
                await self._page.close()
            if self._context:
                await self._context.close()
        except Exception:
            pass
        self._page = None
        self._context = None
        self._resolved = None

    def resolve(self):
        """WebUI 点「完成验证」时调用。"""
        if self._resolved and not self._resolved.done():
            self._resolved.set_result(True)

    def cancel(self):
        if self._resolved and not self._resolved.done():
            self._resolved.set_result(False)

    # ── WebUI 远程操作转发 ─────────────────────────────────────────
    async def screenshot(self) -> Optional[bytes]:
        if not self._page:
            return None
        try:
            return await self._page.screenshot(type="jpeg", quality=70)
        except Exception:
            return None

    async def current_url(self) -> str:
        if not self._page:
            return ""
        try:
            return self._page.url
        except Exception:
            return ""

    async def pointer_down(self, x: float, y: float):
        if self._page:
            await self._page.mouse.move(x, y)
            await self._page.mouse.down()

    async def pointer_move(self, x: float, y: float):
        if self._page:
            await self._page.mouse.move(x, y)

    async def pointer_up(self, x: float, y: float):
        if self._page:
            await self._page.mouse.move(x, y)
            await self._page.mouse.up()

    async def click(self, x: float, y: float):
        if self._page:
            await self._page.mouse.click(x, y)

    async def reload(self):
        if self._page:
            try:
                await self._page.reload(wait_until="domcontentloaded")
            except Exception:
                pass


# 全局单例
captcha_manager = CaptchaManager()
