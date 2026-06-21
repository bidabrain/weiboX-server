"""验证码交互 WebSocket。

连接后服务端持续推送验证码页面截图（base64 jpeg），
客户端把画布上的点击/滑动/按钮事件回传，转发给 Playwright 页面。
鉴权复用 WebUI 的 session cookie。
"""
from __future__ import annotations

import asyncio
import base64

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from ..weibo.captcha import captcha_manager
from .auth import SESSION_COOKIE, _session_valid, _token_valid

router = APIRouter()


def _ws_authorized(ws: WebSocket) -> bool:
    # WebUI：session cookie
    cookie = ws.cookies.get(SESSION_COOKIE, "")
    if cookie and _session_valid(cookie):
        return True
    # app：Bearer Token（握手头 或 ?token= 查询参数）
    if _token_valid(ws.headers.get("authorization", "")):
        return True
    qtoken = ws.query_params.get("token", "")
    if qtoken and _token_valid(f"Bearer {qtoken}"):
        return True
    return False


@router.websocket("/admin/api/captcha/ws")  # WebUI（session cookie）
@router.websocket("/api/v1/captcha/ws")      # app（Bearer Token）
async def captcha_ws(ws: WebSocket):
    if not _ws_authorized(ws):
        await ws.close(code=4401)
        return
    await ws.accept()

    async def send_frames():
        while True:
            if captcha_manager.pending:
                img = await captcha_manager.screenshot()
                if img:
                    await ws.send_json({
                        "type": "frame",
                        "data": base64.b64encode(img).decode("ascii"),
                        "url": await captcha_manager.current_url(),
                    })
                else:
                    await ws.send_json({"type": "state", "pending": True, "loading": True})
            else:
                await ws.send_json({"type": "state", "pending": False})
            await asyncio.sleep(0.6)

    async def recv_events():
        while True:
            msg = await ws.receive_json()
            t = msg.get("type")
            x = float(msg.get("x", 0))
            y = float(msg.get("y", 0))
            if t == "down":
                await captcha_manager.pointer_down(x, y)
            elif t == "move":
                await captcha_manager.pointer_move(x, y)
            elif t == "up":
                await captcha_manager.pointer_up(x, y)
            elif t == "click":
                await captcha_manager.click(x, y)
            elif t == "reload":
                await captcha_manager.reload()
            elif t == "resolve":
                captcha_manager.resolve()
            elif t == "cancel":
                captcha_manager.cancel()

    sender = asyncio.create_task(send_frames())
    receiver = asyncio.create_task(recv_events())
    try:
        await asyncio.wait({sender, receiver}, return_when=asyncio.FIRST_COMPLETED)
    except WebSocketDisconnect:
        pass
    finally:
        sender.cancel()
        receiver.cancel()
