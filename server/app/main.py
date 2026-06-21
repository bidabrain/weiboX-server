"""FastAPI 入口：初始化 DB、启动抓取调度、挂载 API 与 WebUI 静态资源。"""
from __future__ import annotations

import contextlib
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .api import routes_admin, routes_captcha, routes_public
from .config import settings
from .db import init_db
from .services import push
from .services.scraper import scraper
from .weibo.browser import browser_pool

STATIC_DIR = Path(__file__).parent / "web" / "static"


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    push.init()
    scraper.start()
    yield
    await scraper.stop()
    await browser_pool.shutdown()


app = FastAPI(title="WeiboX Server", lifespan=lifespan)

app.include_router(routes_public.router)
app.include_router(routes_admin.router)
app.include_router(routes_captcha.router)


@app.get("/healthz")
def healthz():
    return {"ok": True}


# WebUI：SPA，根路径返回 index.html，其余静态文件挂在 /static
@app.get("/")
def index():
    return FileResponse(STATIC_DIR / "index.html")


app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")
