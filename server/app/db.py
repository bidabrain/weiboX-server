"""SQLAlchemy 引擎与会话。SQLite + WAL，单文件持久化在 data_dir。"""
from __future__ import annotations

from sqlalchemy import create_engine, event, inspect, text
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
    _migrate()


def _migrate() -> None:
    """轻量迁移：只对已存在的表补缺失列（create_all 不会给旧表加列）。

    幂等且不丢数据——旧库只是多一列默认值。
    """
    insp = inspect(engine)
    if "followed_users" in insp.get_table_names():
        cols = {c["name"] for c in insp.get_columns("followed_users")}
        if "special" not in cols:
            with engine.begin() as conn:
                conn.execute(text(
                    "ALTER TABLE followed_users ADD COLUMN special BOOLEAN DEFAULT 0 NOT NULL"
                ))


def get_session() -> Session:
    return SessionLocal()
