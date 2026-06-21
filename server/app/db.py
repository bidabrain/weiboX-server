"""SQLAlchemy 引擎与会话。SQLite + WAL，单文件持久化在 data_dir。"""
from __future__ import annotations

from sqlalchemy import create_engine, event
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import settings


class Base(DeclarativeBase):
    pass


engine = create_engine(
    settings.db_url,
    echo=False,
    connect_args={"check_same_thread": False},
)


@event.listens_for(engine, "connect")
def _set_sqlite_pragma(dbapi_conn, _):
    cur = dbapi_conn.cursor()
    cur.execute("PRAGMA journal_mode=WAL")
    cur.execute("PRAGMA synchronous=NORMAL")
    # 抓取写入与 WebUI/API 读取可能在不同线程并发，busy_timeout 让其等待而非直接报锁
    cur.execute("PRAGMA busy_timeout=5000")
    cur.close()


SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def init_db() -> None:
    # 延迟导入以确保 models 已注册到 Base.metadata
    from . import models  # noqa: F401

    Base.metadata.create_all(engine)


def get_session() -> Session:
    return SessionLocal()
