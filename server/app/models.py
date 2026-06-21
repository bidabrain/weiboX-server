"""ORM 模型。

- FollowedUser：本地关注列表（字段对齐 app 的 WeiboUser，便于 WebDAV 互通），
  额外带 last_fetched_at 用于抓取优先级。
- Post：抓取到的微博，转发内容序列化进 retweet_json。
- Setting：运行期键值配置（cookie / 抓取间隔 / webdav / 开关）。
"""
from __future__ import annotations

from sqlalchemy import Boolean, Float, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base


class FollowedUser(Base):
    __tablename__ = "followed_users"

    id: Mapped[str] = mapped_column(String, primary_key=True)  # 微博 uid
    screen_name: Mapped[str] = mapped_column(String, default="")
    description: Mapped[str] = mapped_column(Text, default="")
    avatar_url: Mapped[str] = mapped_column(String, default="")
    cover_url: Mapped[str] = mapped_column(String, default="")
    followers_count: Mapped[str] = mapped_column(String, default="")
    follow_count: Mapped[int] = mapped_column(Integer, default=0)
    statuses_count: Mapped[int] = mapped_column(Integer, default=0)
    verified: Mapped[bool] = mapped_column(Boolean, default=False)
    verified_reason: Mapped[str] = mapped_column(String, default="")

    # 抓取调度状态（毫秒时间戳，与 app 一致）
    last_fetched_at: Mapped[int] = mapped_column(Integer, default=0)
    added_at: Mapped[int] = mapped_column(Integer, default=0)


class Post(Base):
    __tablename__ = "posts"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    user_id: Mapped[str] = mapped_column(String, index=True, default="")
    user_name: Mapped[str] = mapped_column(String, default="")
    user_avatar: Mapped[str] = mapped_column(String, default="")
    text: Mapped[str] = mapped_column(Text, default="")
    pics_json: Mapped[str] = mapped_column(Text, default="[]")
    created_at: Mapped[str] = mapped_column(String, default="")
    created_at_ts: Mapped[int] = mapped_column(Integer, index=True, default=0)
    likes_count: Mapped[int] = mapped_column(Integer, default=0)
    comments_count: Mapped[int] = mapped_column(Integer, default=0)
    reposts_count: Mapped[int] = mapped_column(Integer, default=0)
    source: Mapped[str] = mapped_column(String, default="")
    is_retweet: Mapped[bool] = mapped_column(Boolean, default=False)
    retweet_json: Mapped[str] = mapped_column(Text, default="")  # 序列化的转发原文
    fetched_at: Mapped[int] = mapped_column(Integer, default=0)


class Setting(Base):
    __tablename__ = "settings"

    key: Mapped[str] = mapped_column(String, primary_key=True)
    value: Mapped[str] = mapped_column(Text, default="")


class DeviceToken(Base):
    """app 上报的 FCM 设备 token，用于推送通知。"""
    __tablename__ = "device_tokens"

    token: Mapped[str] = mapped_column(String, primary_key=True)
    label: Mapped[str] = mapped_column(String, default="")
    created_at: Mapped[int] = mapped_column(Integer, default=0)
    last_seen: Mapped[int] = mapped_column(Integer, default=0)


class ApiToken(Base):
    """对外 API 访问令牌，WebUI 里可新建/删除。app 填其中任一即可连。"""
    __tablename__ = "api_tokens"

    id: Mapped[str] = mapped_column(String, primary_key=True)       # 短随机 id
    token: Mapped[str] = mapped_column(String, unique=True, index=True)  # 令牌明文（高熵随机）
    label: Mapped[str] = mapped_column(String, default="")          # 备注名
    created_at: Mapped[int] = mapped_column(Integer, default=0)
    last_used_at: Mapped[int] = mapped_column(Integer, default=0)
