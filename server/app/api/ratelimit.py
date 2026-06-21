"""登录失败限流 + 取真实客户端 IP。

经 Cloudflare Tunnel 后，uvicorn 看到的来源永远是 127.0.0.1，
真实访客 IP 在 CF-Connecting-IP 头里——限流必须按它来，否则全网算一个 IP。
"""
from __future__ import annotations

import threading
import time

from fastapi import Request


def client_ip(request: Request) -> str:
    """优先 CF-Connecting-IP（Cloudflare），其次 X-Forwarded-For 首跳，最后 socket。"""
    cf = request.headers.get("cf-connecting-ip")
    if cf:
        return cf.strip()
    xff = request.headers.get("x-forwarded-for")
    if xff:
        return xff.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


class LoginLimiter:
    """滑动窗口失败计数：窗口内失败达到上限即锁定，旧记录自然过期解锁。"""

    def __init__(self, max_fails: int = 5, window_sec: int = 900):
        self.max_fails = max_fails
        self.window = window_sec
        self._fails: dict[str, list[float]] = {}
        self._lock = threading.Lock()

    def locked_for(self, ip: str) -> float:
        """返回还需等待的秒数；0 表示未锁定。"""
        now = time.time()
        with self._lock:
            ts = [t for t in self._fails.get(ip, []) if now - t < self.window]
            if ts:
                self._fails[ip] = ts
            else:
                self._fails.pop(ip, None)
            if len(ts) >= self.max_fails:
                return self.window - (now - ts[0])
            return 0.0

    def record_fail(self, ip: str) -> None:
        now = time.time()
        with self._lock:
            self._fails.setdefault(ip, []).append(now)

    def reset(self, ip: str) -> None:
        with self._lock:
            self._fails.pop(ip, None)


login_limiter = LoginLimiter()
