# WeiboX Server —— 含 Playwright Chromium 的一体化镜像
FROM mcr.microsoft.com/playwright/python:v1.49.1-noble

WORKDIR /app

# 依赖层（利用缓存）
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 基础镜像已自带浏览器，但确保 chromium 就绪
RUN python -m playwright install chromium

COPY app ./app

# 数据目录（SQLite / token / secret）。建议用卷挂载持久化。
ENV WEIBOX_DATA_DIR=/data
VOLUME ["/data"]
EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
