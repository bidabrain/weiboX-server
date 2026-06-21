# WeiboX

**自托管的第三方微博方案：服务端持续抓取 + 轻量安卓客户端。**

WeiboX 分两部分：一个长期运行的**服务端**（`server/`）负责定时抓取你关注用户的微博、扛微博的防爬与验证码；一个**安卓客户端**（`app/`）只跟你自己的服务端通信，浏览体验完整（时间线、主页、关注列表、评论、搜索、关注管理）。

```
   安卓 app  ──HTTPS /api/v1──►  你的 WeiboX Server  ──►  m.weibo.cn
  (只连 server)                  (扛防爬/验证码/缓存)      (真实数据源)
```

## 为什么是客户端-服务器

早期 WeiboX 是单机 app 直连微博，但微博有防爬，必须前后台限速抓取、还要在端上处理验证码。改成服务端架构后：

- **服务端扛防爬**：长期运行、可把抓取间隔调长，遍历全部关注用户定时抓取并缓存。
- **app 极简**：拉的是服务端**已缓存**的数据，无防爬顾虑，打开即同步、下拉刷新，无任何后台服务。
- **验证码远程解**：服务端撞到验证码 → 推送通知到 app（或 WebUI）→ 你在界面里实时点/滑解锁 → 服务端继续。
- **多端共享**：服务端是单一数据源，关注列表 / 抓取 / Cookie 都在服务端集中管理。

## 组成

| 目录 | 说明 |
|---|---|
| [`server/`](server/README.md) | **WeiboX Server** —— FastAPI 服务端：定时抓取、访客 session 引导、验证码交互、统一 `/api/v1`、WebUI 管理后台、WebDAV 备份、FCM 推送、Docker 部署。**详见 [server/README.md](server/README.md)**。 |
| `app/` | **安卓客户端** —— Jetpack Compose 客户端，数据全部来自你的服务端。 |

## 快速开始

### 1. 部署服务端

最简单是 Docker（镜像已含 Playwright/Chromium）：

```bash
cd server
echo "WEIBOX_ADMIN_PASSWORD=你的强密码" > .env
docker compose up -d --build
```

打开 `http://localhost:8000` 登录 → 设置里**新建 API Token**、搜索并关注用户。
完整部署（本地运行、Cloudflare Tunnel 公网、Docker Hub 发布）见 **[server/README.md](server/README.md)**。

### 2. 编译安卓客户端

> ⚠️ 本仓库**不含** `app/google-services.json`（FCM 客户端配置，各人用自己的 Firebase 项目）。
> 先在 [Firebase 控制台](https://console.firebase.google.com/) 建项目、注册 Android app
> （包名 `com.weibox.app`）、下载 `google-services.json` 放到 **`app/google-services.json`**，再编译。

```bash
# 放好 app/google-services.json 后
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 不需要推送（FCM）的话，验证码也可以始终在服务端 WebUI 里解；但 app 应用了 google-services 插件，
> 编译仍要求该文件存在。

### 3. 客户端连接服务端

装好 app → **设置** → 填**服务器地址**（如 `https://weibo.example.com`，局域网联调用 `http://192.168.x.x:8000`）+ **API Token** → 测试连接。之后时间线/主页/评论/搜索/关注全走你的服务端。

## 技术栈

**服务端**：Python · FastAPI · httpx（抓取）· Playwright（访客 session 引导 + 验证码无头浏览器）· SQLite · firebase-admin（FCM）· 原生 JS WebUI · Docker

**客户端**：Kotlin · Jetpack Compose + Material3 · MVVM + Hilt · Room（本地缓存镜像）· OkHttp · Coil · Firebase Messaging（验证码推送 + app 内解锁）

## FCM 验证码推送（可选）

服务端抓取撞到验证码时，可推送通知到手机：点通知 → app 打开验证码界面 → 实时显示验证码截图、手指点/滑 → 完成后服务端继续。需要：服务端放你 Firebase 项目的私钥 `server/data/firebase-key.json`，客户端用同项目的 `google-services.json`。不配则推送关闭，验证码仍可在服务端 WebUI 里解。

## 项目结构

```
weiboX/
├── app/                      # 安卓客户端（Jetpack Compose）
│   └── src/main/java/com/weibox/app/
│       ├── MainActivity.kt           # 入口 + 通知权限 + 设备 token 上报
│       ├── CaptchaActivity.kt        # app 内验证码界面（WS 截图流 + 触摸转发）
│       ├── data/
│       │   ├── api/ServerApi.kt       # ★ 调服务端 /api/v1
│       │   ├── repository/            # Room 缓存镜像 + 服务端数据源
│       │   ├── db/ · model/ · prefs/
│       ├── fcm/                       # FirebaseMessagingService + 设备注册
│       └── ui/                        # 时间线 / 主页 / 搜索 / 关注 / 设置
│
├── server/                   # WeiboX Server（FastAPI）—— 详见 server/README.md
│   ├── app/
│   │   ├── weibo/            # 抓取 client / 解析 / 访客 session / 验证码
│   │   ├── services/         # 定时抓取调度 / 数据读写 / WebDAV / FCM 推送
│   │   ├── api/              # /api/v1（浏览）· /admin（管理）· 验证码 WS
│   │   └── web/static/       # WebUI 管理后台
│   ├── Dockerfile · docker-compose.yml · docker-compose.hub.yml
│   └── README.md            # 服务端完整文档
└── README.md                # 本文件
```

## 致谢

服务端的网络请求逻辑、接口地址、请求头与限流策略，参考自开源项目 **[dataabc/weibo-crawler](https://github.com/dataabc/weibo-crawler)**。WeiboX 服务端可理解为将其数据获取层用 Python 重新组织、并加上调度 / WebUI / 验证码交互 / API 的版本。感谢原项目作者的持续维护。

## Star History

<a href="https://www.star-history.com/?repos=bidabrain%2FweiboX-server&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=bidabrain/weiboX-server&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=bidabrain/weiboX-server&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=bidabrain/weiboX-server&type=date&legend=top-left" />
 </picture>
</a>
