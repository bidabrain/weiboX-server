"""定时抓取调度。

与 app 不同：服务器不分前后台、不限抓取人数，遍历全部关注用户，
请求间隔按设置可调长（默认 30~60s）。命中验证码时拉起 Playwright，
等 WebUI 人工解完拿回新 cookie 再继续。
"""
from __future__ import annotations

import asyncio
import random
import time
import traceback
from typing import List, Optional, Tuple

from .. import store
from ..weibo.auth_cookie import effective_cookie
from ..weibo.captcha import captcha_manager
from ..weibo.client import CaptchaRequired, SessionInvalid, WeiboClient, WeiboError
from . import push, repo

# 优先级估算用的下限（毫秒）
DEFAULT_POST_INTERVAL_MS = 6 * 60 * 60 * 1000
MIN_POST_INTERVAL_MS = 30 * 60 * 1000

# _fetch_user_with_captcha 的哨兵返回值：需刷新会话后重试
RETRY_SESSION = object()


class ScraperStatus:
    def __init__(self):
        self.enabled = True
        self.round_running = False
        self.last_round_started = 0
        self.last_round_finished = 0
        self.next_round_at = 0
        self.last_error = ""
        self.current_user = ""
        self.progress_done = 0
        self.progress_total = 0
        self.last_round_new_posts = 0
        self.last_round_new_hot = 0

    def snapshot(self) -> dict:
        return {
            "enabled": self.enabled,
            "round_running": self.round_running,
            "last_round_started": self.last_round_started,
            "last_round_finished": self.last_round_finished,
            "next_round_at": self.next_round_at,
            "last_error": self.last_error,
            "current_user": self.current_user,
            "progress_done": self.progress_done,
            "progress_total": self.progress_total,
            "last_round_new_posts": self.last_round_new_posts,
            "last_round_new_hot": self.last_round_new_hot,
            "captcha_pending": captcha_manager.pending,
            "captcha_url": captcha_manager.captcha_url,
            "total_posts": repo.post_count(),
            "total_hot": repo.hot_count(),
        }


class Scraper:
    def __init__(self):
        self.status = ScraperStatus()
        self._task: Optional[asyncio.Task] = None
        self._wake = asyncio.Event()
        self._stop = False
        self._run_now = False

    # ── 生命周期 ───────────────────────────────────────────────────
    def start(self):
        if self._task is None or self._task.done():
            self._stop = False
            self._task = asyncio.create_task(self._loop())

    async def stop(self):
        self._stop = True
        self._wake.set()
        if self._task:
            try:
                await asyncio.wait_for(self._task, timeout=5)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                self._task.cancel()

    def trigger_now(self):
        """请求立即跑一轮。"""
        self._run_now = True
        self._wake.set()

    async def _loop(self):
        while not self._stop:
            enabled = store.get_bool("scrape_enabled")
            self.status.enabled = enabled
            run_now = self._run_now
            self._run_now = False

            if enabled or run_now:
                try:
                    await self.run_round()
                except Exception as e:
                    self.status.last_error = f"{e}\n{traceback.format_exc()}"

            # 计算下次唤醒时间
            interval = max(30, store.get_int("round_interval_sec"))
            self.status.next_round_at = int(time.time() * 1000) + interval * 1000
            self._wake.clear()
            try:
                await asyncio.wait_for(self._wake.wait(), timeout=interval)
            except asyncio.TimeoutError:
                pass

    # ── 抓取一轮 ───────────────────────────────────────────────────
    def _build_candidates(self) -> List[str]:
        """按逾期率排序的全部待抓用户 uid（跳过 min_check_interval 内抓过的）。"""
        users = repo.list_users()
        if not users:
            return []
        now = int(time.time() * 1000)
        min_check = store.get_int("min_check_interval_sec") * 1000
        stats = repo.post_stats_by_user()

        scored: List[Tuple[str, float]] = []
        for u in users:
            since = now - (u.get("last_fetched_at") or 0)
            if since < min_check:
                continue
            st = stats.get(u["id"])
            if st and st["count"] > 1:
                avg = (st["newest"] - st["oldest"]) / (st["count"] - 1)
            else:
                avg = DEFAULT_POST_INTERVAL_MS
            avg = max(avg, MIN_POST_INTERVAL_MS)
            priority = since / avg if avg else since
            scored.append((u["id"], priority))
        scored.sort(key=lambda x: x[1], reverse=True)
        return [uid for uid, _ in scored]

    async def run_round(self):
        if self.status.round_running:
            return
        candidates = self._build_candidates()
        self.status.round_running = True
        self.status.last_round_started = int(time.time() * 1000)
        self.status.last_error = ""
        self.status.progress_total = len(candidates)
        self.status.progress_done = 0
        self.status.last_round_new_posts = 0
        self.status.last_round_new_hot = 0

        hot_enabled = store.get_bool("hot_enabled")
        if not candidates and not hot_enabled:
            self.status.round_running = False
            self.status.last_round_finished = int(time.time() * 1000)
            return

        cookie, is_visitor = await self._effective_cookie()
        client = WeiboClient(cookie)
        delay_min = max(1, store.get_int("req_delay_min_sec"))
        delay_max = max(delay_min, store.get_int("req_delay_max_sec"))
        count = max(1, store.get_int("posts_per_user"))
        new_total = 0

        try:
            for index, uid in enumerate(candidates):
                if self._stop:
                    break
                if index > 0:
                    await asyncio.sleep(random.uniform(delay_min, delay_max))

                self.status.current_user = uid
                posts = await self._fetch_user_with_captcha(client, uid, count)
                if posts is RETRY_SESSION:
                    # 访客 session 失效：刷新后重建客户端，重试当前用户
                    await client.aclose()
                    cookie, is_visitor = await self._effective_cookie(force_visitor=True)
                    client = WeiboClient(cookie)
                    posts = await self._fetch_user_with_captcha(client, uid, count)
                    if posts is RETRY_SESSION:
                        posts = []
                if posts is None:
                    # 验证码未解决，终止本轮
                    self.status.last_error = "验证码未完成，本轮中止"
                    break
                if posts:
                    repo.save_posts(posts)
                    new_total += len(posts)
                    repo.backfill_profile(uid, posts[0].get("user_name", ""), posts[0].get("user_avatar", ""))
                repo.update_last_fetched(uid, int(time.time() * 1000))
                self.status.progress_done = index + 1

            # 热门流：和关注列表同轮抓取，存入独立的 hot_posts 表
            if hot_enabled and not self._stop:
                if candidates:
                    await asyncio.sleep(random.uniform(delay_min, delay_max))
                self.status.current_user = "热门流"
                hot = await self._fetch_hot(client)
                if hot is RETRY_SESSION:
                    await client.aclose()
                    cookie, is_visitor = await self._effective_cookie(force_visitor=True)
                    client = WeiboClient(cookie)
                    hot = await self._fetch_hot(client)
                if isinstance(hot, list) and hot:
                    repo.save_hot_posts(hot)
                    self.status.last_round_new_hot = len(hot)
        finally:
            await client.aclose()
            retention = store.get_int("post_retention_days")
            repo.trim_posts(retention, store.get_int("max_cached_posts"))
            repo.trim_hot_posts(retention, store.get_int("max_cached_hot"))
            self.status.last_round_new_posts = new_total
            self.status.current_user = ""
            self.status.round_running = False
            self.status.last_round_finished = int(time.time() * 1000)

    async def _effective_cookie(self, force_visitor: bool = False):
        try:
            return await effective_cookie(force_visitor=force_visitor)
        except Exception as e:
            self.status.last_error = f"访客 session 获取失败：{e}"
            return store.get("cookie").strip(), False

    async def _fetch_user_with_captcha(self, client: WeiboClient, uid: str, count: int):
        """抓单个用户。返回 posts 列表 / None（验证码放弃）/ RETRY_SESSION（需刷新会话）。"""
        try:
            return await client.get_user_posts(uid, page=1, count=count)
        except SessionInvalid:
            return RETRY_SESSION
        except CaptchaRequired as e:
            # 推送通知到 app，让用户点开在 app 内解锁（WebUI 同时也会弹横幅）
            try:
                await asyncio.to_thread(push.notify_captcha, e.captcha_url)
            except Exception:
                pass
            new_cookie = await captcha_manager.solve(e.captcha_url, client.export_cookie_string())
            if not new_cookie:
                return None
            client.merge_cookies(new_cookie)
            # 解完的 cookie 持久化（仅当用户本就配置了 cookie 时覆盖）
            if store.get("cookie").strip():
                store.set_value("cookie", client.export_cookie_string())
            try:
                return await client.get_user_posts(uid, page=1, count=count)
            except CaptchaRequired:
                return None
            except (WeiboError, SessionInvalid):
                return []
        except WeiboError as e:
            self.status.last_error = str(e)
            return []

    async def _fetch_hot(self, client: WeiboClient):
        """抓热门流。返回 posts 列表 / RETRY_SESSION（需刷新会话）/ [] （其他失败时跳过，不影响本轮）。

        热门为次要内容：命中验证码时直接跳过本轮热门，不打断已抓到的关注数据。
        """
        containerid = store.get("hot_containerid").strip() or "102803"
        count = max(1, store.get_int("hot_count"))
        try:
            return await client.get_hot_posts(containerid=containerid, count=count)
        except SessionInvalid:
            return RETRY_SESSION
        except CaptchaRequired:
            self.status.last_error = "热门流命中验证码，本轮跳过"
            return []
        except WeiboError as e:
            self.status.last_error = f"热门流抓取失败：{e}"
            return []


scraper = Scraper()
