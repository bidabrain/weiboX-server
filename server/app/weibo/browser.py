"""共享的 Playwright Chromium 实例。验证码交互与访客 session 引导共用一个浏览器。"""
from __future__ import annotations

import asyncio
from typing import Optional

from ..config import settings

LAUNCH_ARGS = ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"]


class BrowserPool:
    def __init__(self):
        self._playwright = None
        self._browser = None
        self._lock = asyncio.Lock()

    async def browser(self):
        if not settings.enable_browser:
            raise RuntimeError("未启用无头浏览器（WEIBOX_ENABLE_BROWSER=false）")
        async with self._lock:
            if self._browser is None or not self._browser.is_connected():
                from playwright.async_api import async_playwright
                if self._playwright is None:
                    self._playwright = await async_playwright().start()
                self._browser = await self._playwright.chromium.launch(
                    headless=True, args=LAUNCH_ARGS,
                )
        return self._browser

    async def shutdown(self):
        try:
            if self._browser:
                await self._browser.close()
                self._browser = None
            if self._playwright:
                await self._playwright.stop()
                self._playwright = None
        except Exception:
            pass


browser_pool = BrowserPool()
