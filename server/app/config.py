"""运行期配置：来自环境变量 / .env，少量来自数据库 settings 表（运行时可改）。

环境变量只放部署期不变的东西（端口、密码、数据目录）。
抓取间隔、cookie、webdav 等可在 WebUI 运行时修改的项放数据库。
"""
from __future__ import annotations

import os
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="WEIBOX_", env_file=".env", extra="ignore")

    # 服务监听
    host: str = "0.0.0.0"
    port: int = 8000

    # 数据目录（SQLite 与运行期文件）。Docker 里挂卷到此目录即可持久化。
    data_dir: str = "./data"

    # WebUI 管理密码。首次部署务必通过环境变量覆盖。
    admin_password: str = "admin"

    # 可选的「主」Bearer Token（环境变量设置时永久有效，方便自动化）。
    # 留空则只用 WebUI 里管理的数据库令牌（推荐）。
    api_token: str = ""

    # 会话签名密钥。留空则启动时自动生成并持久化到 data_dir/secret_key.txt。
    secret_key: str = ""

    # 是否启用 Playwright 无头浏览器（验证码交互 / 访客 session 引导）。
    enable_browser: bool = True

    # FCM 服务账号私钥。留空则用 data_dir/firebase-key.json；也可设绝对路径。
    # 文件存在则启用推送，否则自动关闭。
    firebase_key: str = ""

    @property
    def firebase_key_path(self) -> Path:
        if self.firebase_key:
            return Path(self.firebase_key)
        return Path(self.data_dir) / "firebase-key.json"

    @property
    def db_path(self) -> Path:
        return Path(self.data_dir) / "weibox.db"

    @property
    def db_url(self) -> str:
        return f"sqlite:///{self.db_path}"


def _load_or_create(path: Path, generator) -> str:
    if path.exists():
        return path.read_text().strip()
    value = generator()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value)
    return value


settings = Settings()
Path(settings.data_dir).mkdir(parents=True, exist_ok=True)

# 密钥/Token 持久化：未显式配置则自动生成一次并落盘，重启保持不变。
if not settings.secret_key:
    settings.secret_key = _load_or_create(
        Path(settings.data_dir) / "secret_key.txt", lambda: os.urandom(32).hex()
    )
# api_token 不再自动生成：对外令牌改由 WebUI 设置里管理（数据库）。
# 仅当通过 WEIBOX_API_TOKEN 显式设置时，作为额外的「主令牌」永久有效。
