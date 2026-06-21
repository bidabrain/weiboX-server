"""运行期键值设置的读写封装，带类型转换与默认值。

这些项可在 WebUI 运行时修改，存数据库 settings 表。
"""
from __future__ import annotations

from typing import Optional

from .db import get_session
from .models import Setting

# 设置键 + 默认值（字符串存储）
DEFAULTS = {
    "cookie": "",                       # 微博 cookie（主登录方式）
    "scrape_enabled": "true",           # 定时抓取总开关
    "round_interval_sec": "1800",       # 每轮全量抓取的间隔（秒），默认 30 分钟
    "req_delay_min_sec": "30",          # 单个用户请求之间的最小延迟（秒）
    "req_delay_max_sec": "60",          # 单个用户请求之间的最大延迟（秒）
    "posts_per_user": "20",             # 每用户每轮抓取的微博条数
    "min_check_interval_sec": "300",    # 该用户多久内抓过则跳过（秒）
    "post_retention_days": "30",        # 微博保留天数（0 = 不按时间清理）
    "max_cached_posts": "5000",         # 微博缓存条数上限（0 = 不限）
    "webdav_url": "",
    "webdav_user": "",
    "webdav_pass": "",
}


def get(key: str) -> str:
    with get_session() as s:
        row = s.get(Setting, key)
        if row is not None:
            return row.value
    return DEFAULTS.get(key, "")


def get_int(key: str) -> int:
    try:
        return int(float(get(key)))
    except (ValueError, TypeError):
        return int(float(DEFAULTS.get(key, "0") or "0"))


def get_bool(key: str) -> bool:
    return get(key).strip().lower() in ("1", "true", "yes", "on")


def set_value(key: str, value: str) -> None:
    with get_session() as s:
        row = s.get(Setting, key)
        if row is None:
            row = Setting(key=key, value=value)
            s.add(row)
        else:
            row.value = value
        s.commit()


def set_many(values: dict) -> None:
    with get_session() as s:
        for key, value in values.items():
            row = s.get(Setting, key)
            if row is None:
                s.add(Setting(key=key, value=str(value)))
            else:
                row.value = str(value)
        s.commit()


def all_settings() -> dict:
    result = dict(DEFAULTS)
    with get_session() as s:
        for row in s.query(Setting).all():
            result[row.key] = row.value
    return result
