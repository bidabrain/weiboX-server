# WeiboX Server

WeiboX 安卓客户端的**服务器版本**：长期运行、定时抓取关注用户的微博，并对外提供 API 供第三方客户端（如 WeiboX App）直接调取。带 WebUI 管理后台。

与安卓 app 的关系：复用同一套 `m.weibo.cn` 接口与限流策略，但作为服务器：

- 不分前后台，**遍历全部关注用户**定时抓取，抓取间隔可调长以防反爬；
- 抓到的微博持久化在服务器，客户端通过 API 拉取，无需自己抓；
- WebDAV 备份格式与 app **完全兼容**（仅备份关注列表 + Cookie）。

## 功能

- **定时全量抓取**：按「逾期率」给关注用户排优先级，请求间隔随机（默认 30~60s，可配）。
- **两种登录**：配置微博 Cookie（主），或留空走**访客 session**（无头浏览器自动引导，复刻 app 的 WebView incarnate 流程）。
- **验证码人工处理**：命中反爬验证码时，WebUI 实时显示验证码页面截图，你直接在浏览器里点击/滑动完成，解完无缝继续。
- **WebUI 管理**：搜索/关注/取关用户、看时间线、改 Cookie 与抓取参数、WebDAV 备份恢复。
- **对外 API**：Bearer Token 鉴权，提供聚合时间线 / 用户微博 / 关注列表 / 状态。
- **WebDAV 备份**：与安卓 app 的 `weibox_backup.json` v2 格式互通。

## 本地运行

```bash
cd server
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python -m playwright install chromium          # 验证码/访客 session 需要

export WEIBOX_ADMIN_PASSWORD=你的密码
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

打开 http://localhost:8000 ，用管理密码登录。
对外 API 的 Token 在 **WebUI「设置 → API Token」里新建/删除**，app 填「服务器地址 + 任一 Token」即可连接。

> 本地若不想装浏览器，设 `WEIBOX_ENABLE_BROWSER=false`：此时验证码与访客 session 不可用，
> 需在设置里手动粘贴有效 Cookie 才能抓取。

## Docker 部署

镜像基于官方 Playwright 镜像（已含 Chromium）。所有数据持久化在挂载的 `./data`
（SQLite、密钥、可选的 firebase-key.json）——**镜像本身不含任何用户数据**：
`.dockerignore` 排除 `data/`，Dockerfile 只打包 `app/`。所以镜像是干净的，
每个部署用空 `data/` 启动后，自己在 WebUI 新建 API Token、重新关注。

### 本地构建并运行

```bash
cd server
echo "WEIBOX_ADMIN_PASSWORD=你的强密码" > .env   # compose 自动读取
docker compose up -d --build
```

首次构建要几分钟（拉 ~1.8GB 基础镜像 + 装依赖）。验证：

```bash
docker compose ps             # STATUS 应为 healthy
curl localhost:8000/healthz   # {"ok":true}
docker compose logs -f        # 实时日志
```

常用命令：`docker compose restart` / `down`（数据留在 ./data 不丢）/
`up -d --build`（改代码后重建）。

### 构建并推送到 Docker Hub

先 `docker login`。推荐多架构（x86 + ARM 服务器都能跑）：

```bash
cd server
docker buildx create --use --name weibox-builder        # 一次性
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t YOUR_DOCKERHUB_USER/weibox-server:latest \
  -t YOUR_DOCKERHUB_USER/weibox-server:1.0.0 \
  --push .
```

或单架构：

```bash
cd server
docker build -t YOUR_DOCKERHUB_USER/weibox-server:latest .
docker push YOUR_DOCKERHUB_USER/weibox-server:latest
```

> 把 `YOUR_DOCKERHUB_USER` 换成你的 Docker Hub 用户名。
> 验证镜像干净：`docker run --rm YOUR_DOCKERHUB_USER/weibox-server:latest ls -la /data`（应为空）。

### 运行已发布的镜像（全新实例）

用仓库里的 `docker-compose.hub.yml`，在一个**空目录**里启动即得干净服务器：

```bash
mkdir weibox && cd weibox
# 放入 docker-compose.hub.yml
cat > .env <<EOF
WEIBOX_IMAGE=YOUR_DOCKERHUB_USER/weibox-server:latest
WEIBOX_ADMIN_PASSWORD=强密码
EOF
docker compose -f docker-compose.hub.yml up -d
```

打开 `http://localhost:8000` 登录 → 设置里新建 API Token、搜索关注。

### 数据与备份

- 全部状态在 `./data`：`weibox.db`（关注列表 / 微博缓存 / API Token / 设备 token）、
  `secret_key.txt`（会话签名）、可选 `firebase-key.json`（FCM 私钥）。
- 备份直接备份整个 `data/` 目录即可。
- **推送（FCM）**：把你 Firebase 项目的私钥放到该机的 `./data/firebase-key.json`，
  容器启动自动启用；留空则推送关闭、其余功能照常。镜像里**不含**该私钥（每个部署者用自己的项目）。

## API（/api/v1）

一套接口，**WebUI（登录 session）与第三方客户端（Bearer Token）共用**。
客户端调用带 `Authorization: Bearer <token>`；WebUI 浏览器自动带 cookie。

> **API Token** 在 WebUI「设置 → API Token」里管理：可新建（随机生成）/ 删除多个，
> 每个可加备注名，丢失或泄露就删掉对应 Token，不影响其他设备。
> 也可用环境变量 `WEIBOX_API_TOKEN` 设一个固定「主令牌」（可选）。

分两类：**缓存数据**（定时抓取入库，快）与**实时数据**（点开才即时代理微博）。

| 接口 | 类型 | 说明 |
|---|---|---|
| `GET /api/v1/status` | — | 抓取状态 |
| `GET /api/v1/timeline?limit=50&offset=0` | 缓存 | 聚合时间线（所有关注用户，按时间倒序） |
| `GET /api/v1/users` | 库 | 关注列表 |
| `POST /api/v1/users` `{"id":"<uid>"}` | 库 | 关注（自动补全资料） |
| `DELETE /api/v1/users/{uid}` | 库 | 取关 |
| `GET /api/v1/users/{uid}` | 实时 | 用户主页详情 |
| `GET /api/v1/users/{uid}/posts?page=1&count=20` | 实时 | 该用户微博（翻页） |
| `GET /api/v1/users/{uid}/following?page=2` | 实时 | 该用户的关注列表 |
| `GET /api/v1/posts/{mid}/comments?max_id=&page=1` | 实时 | 评论（`next_max_id` 翻页） |
| `GET /api/v1/search?q=关键词` | 实时 | 搜索用户（关键词 / 数字 UID） |
| `GET /api/v1/img?u=<图片URL>` | 代理 | 图片防盗链代理（仅微博域名） |

管理类接口在 `/admin/api`（仅登录 session）：`login` / `settings` / `webdav/*` /
`scrape-now` / `captcha/ws`。

示例：

```bash
curl -H "Authorization: Bearer <在WebUI设置里新建的Token>" \
     "http://localhost:8000/api/v1/timeline?limit=20"
```

## 安全与公网部署

推荐用 **Cloudflare Tunnel** 暴露到自己的域名（CF 边缘自动 HTTPS + 抗 DDoS）：

1. **只绑回环**：cloudflared 连 `localhost:8000`，端口别暴露到公网接口
   （docker-compose 已设 `127.0.0.1:8000:8000`；本地 uvicorn 默认即 127.0.0.1）。
2. **单 worker 运行**：抓取调度/验证码用进程内单例，**切勿** `--workers >1`，
   否则会多份抓取循环重复抓、触发风控，验证码状态也不共享。
3. **强随机** `WEIBOX_ADMIN_PASSWORD`（务必改掉默认）。
4. API Token 在 WebUI 里按设备签发，丢失即删。

已内置的加固：登录失败按 IP 限流（读 `CF-Connecting-IP`）、图片代理逐跳校验
域名防 SSRF、HTTPS 下 session cookie 自动加 `Secure`、ORM 参数化防注入、
token/密码常数时间比对。

可选再加一层：把 WebUI 域名放到 **Cloudflare Access** 后面（边缘身份验证），
`/api/v1`（给 app 用）保持 Token 鉴权。

## 配置项

部署期配置走环境变量（见 `.env.example`）：`WEIBOX_ADMIN_PASSWORD` / `WEIBOX_API_TOKEN` /
`WEIBOX_ENABLE_BROWSER` / `WEIBOX_DATA_DIR` / `WEIBOX_PORT`。

运行期配置在 WebUI「设置」里改（存数据库）：Cookie、抓取开关、每轮间隔、请求延迟、
每用户条数、跳过间隔、保留天数、缓存上限、WebDAV 地址/账号。

## 目录结构

```
server/app/
├── main.py              # FastAPI 入口
├── config.py            # 环境变量配置
├── db.py / models.py    # SQLite + ORM
├── store.py             # 运行期键值设置
├── weibo/
│   ├── client.py        # m.weibo.cn / s.weibo.com 抓取（移植自 app WeiboApi.kt）
│   ├── parser.py        # JSON/HTML 解析
│   ├── browser.py       # 共享 Playwright Chromium
│   ├── visitor.py       # 访客 session 引导（incarnate 流程）
│   └── captcha.py       # 验证码交互协调
├── services/
│   ├── scraper.py       # 定时抓取调度
│   ├── repo.py          # 用户/微博读写
│   └── webdav.py        # 与 app 兼容的 WebDAV 备份
├── api/                 # 公开 API / WebUI 后端 / 验证码 WebSocket / 鉴权
└── web/static/          # WebUI（原生 JS）
```
