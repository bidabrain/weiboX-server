"use strict";

// ── fetch 封装 ──────────────────────────────────────────────────
async function api(path, opts = {}) {
  const res = await fetch(path, { headers: { "Content-Type": "application/json" }, ...opts });
  if (res.status === 401) { showLogin(); throw new Error("未登录"); }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.detail || `HTTP ${res.status}`);
  return data;
}
const $ = (id) => document.getElementById(id);
const esc = (s) => (s || "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
// 微博图片防盗链，统一走服务端代理
const img = (url) => (url ? "/api/v1/img?u=" + encodeURIComponent(url) : "");

// ── 视图切换 ────────────────────────────────────────────────────
function showLogin() { $("login-view").classList.remove("hidden"); $("main-view").classList.add("hidden"); stopPolling(); }
function showMain() {
  $("login-view").classList.add("hidden");
  $("main-view").classList.remove("hidden");
  startPolling(); loadSettings(); loadFollowing(); loadTokens();
}

// ── 登录 ────────────────────────────────────────────────────────
$("login-form").addEventListener("submit", async (e) => {
  e.preventDefault(); $("login-error").textContent = "";
  try {
    await api("/admin/api/login", { method: "POST", body: JSON.stringify({ password: $("login-password").value }) });
    $("login-password").value = ""; showMain();
  } catch (err) { $("login-error").textContent = err.message; }
});
$("logout-btn").addEventListener("click", async () => {
  await api("/admin/api/logout", { method: "POST" }).catch(() => {});
  showLogin();
});

// ── 标签页 ──────────────────────────────────────────────────────
document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
    tab.classList.add("active");
    const name = tab.dataset.tab;
    document.querySelectorAll(".tab-panel").forEach((p) => p.classList.add("hidden"));
    $("tab-" + name).classList.remove("hidden");
    if (name === "timeline") loadTimeline();
    if (name === "hot") loadHot();
    if (name === "following") loadFollowing();
  });
});

// ── 状态轮询 ────────────────────────────────────────────────────
let pollTimer = null;
function startPolling() { stopPolling(); refreshStatus(); pollTimer = setInterval(refreshStatus, 3000); }
function stopPolling() { if (pollTimer) clearInterval(pollTimer); pollTimer = null; }
function fmtTime(ms) { return ms ? new Date(ms).toLocaleString("zh-CN") : "—"; }

async function refreshStatus() {
  let s; try { s = await api("/api/v1/status"); } catch { return; }
  $("st-enabled").textContent = s.enabled ? "开" : "关";
  $("st-running").textContent = s.round_running ? "抓取中" : "空闲";
  $("st-progress").textContent = `${s.progress_done}/${s.progress_total}`;
  $("st-posts").textContent = s.total_posts;
  $("st-new").textContent = s.last_round_new_posts;
  $("st-next").textContent = fmtTime(s.next_round_at);
  $("st-current").textContent = s.current_user ? `正在抓取 UID ${s.current_user}` : "";
  $("st-error").textContent = s.last_error || "";
  $("captcha-banner").classList.toggle("hidden", !s.captcha_pending);
}
$("scrape-now").addEventListener("click", async () => { await api("/admin/api/scrape-now", { method: "POST" }); refreshStatus(); });

// ── 关注/取关 + 特别关注（通用）─────────────────────────────────
async function doFollow(uid) { await api("/api/v1/users", { method: "POST", body: JSON.stringify({ id: uid }) }); }
async function doUnfollow(uid) { await api("/api/v1/users/" + encodeURIComponent(uid), { method: "DELETE" }); }
async function doSpecial(uid) { await api("/api/v1/users/" + encodeURIComponent(uid) + "/special", { method: "POST" }); }
async function doUnspecial(uid) { await api("/api/v1/users/" + encodeURIComponent(uid) + "/special", { method: "DELETE" }); }

// ── 用户卡片渲染（搜索 / 关注列表 / TA的关注 共用）──────────────
function userItemHtml(u) {
  const followed = !!u.followed;
  const special = !!u.special;
  const star = special
    ? `<button class="star-btn active" data-unspecial="${esc(u.id)}" title="取消特别关注">★</button>`
    : `<button class="star-btn" data-special="${esc(u.id)}" title="特别关注">☆</button>`;
  const btn = followed
    ? `<button class="ghost small-btn" data-unfollow="${esc(u.id)}">取关</button>`
    : `<button class="small-btn" data-follow="${esc(u.id)}">关注</button>`;
  return `<div class="user-item">
    <img src="${esc(img(u.avatar_url))}" data-uid="${esc(u.id)}" class="clk" onerror="this.style.visibility='hidden'"/>
    <div class="u-main clk" data-uid="${esc(u.id)}">
      <div class="u-name">${esc(u.screen_name)} ${u.verified ? "✔" : ""}</div>
      <div class="u-desc">${esc(u.description || "")} · 粉丝 ${esc(String(u.followers_count || ""))}</div>
    </div>${star}${btn}</div>`;
}
function bindUserList(container, onChange) {
  container.querySelectorAll("[data-uid].clk").forEach((el) =>
    el.addEventListener("click", () => openProfile(el.dataset.uid)));
  container.querySelectorAll("[data-follow]").forEach((b) =>
    b.addEventListener("click", async (e) => { e.stopPropagation(); b.disabled = true; await doFollow(b.dataset.follow); onChange && onChange(); }));
  container.querySelectorAll("[data-unfollow]").forEach((b) =>
    b.addEventListener("click", async (e) => { e.stopPropagation(); b.disabled = true; await doUnfollow(b.dataset.unfollow); onChange && onChange(); }));
  container.querySelectorAll("[data-special]").forEach((b) =>
    b.addEventListener("click", async (e) => { e.stopPropagation(); b.disabled = true; await doSpecial(b.dataset.special); onChange && onChange(); }));
  container.querySelectorAll("[data-unspecial]").forEach((b) =>
    b.addEventListener("click", async (e) => { e.stopPropagation(); b.disabled = true; await doUnspecial(b.dataset.unspecial); onChange && onChange(); }));
}
function renderUsers(container, users, onChange) {
  if (!users.length) { container.innerHTML = '<div class="muted">无结果</div>'; return; }
  container.innerHTML = users.map(userItemHtml).join("");
  bindUserList(container, onChange);
}

// ── 搜索 & 关注管理 ─────────────────────────────────────────────
$("search-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const q = $("search-input").value.trim(); if (!q) return;
  $("search-results").innerHTML = '<div class="muted">搜索中…</div>';
  try {
    const data = await api("/api/v1/search?q=" + encodeURIComponent(q));
    renderUsers($("search-results"), data.users, () => { runSearch(q); loadFollowing(); });
  } catch (err) { $("search-results").innerHTML = `<div class="error">${esc(err.message)}</div>`; }
});
async function runSearch(q) {
  try { const d = await api("/api/v1/search?q=" + encodeURIComponent(q)); renderUsers($("search-results"), d.users, () => { runSearch(q); loadFollowing(); }); } catch {}
}
async function loadFollowing() {
  try {
    const data = await api("/api/v1/users");
    $("following-count").textContent = `(${data.users.length})`;
    renderUsers($("following-list"), data.users, loadFollowing);
  } catch {}
  loadSpecial();
}
async function loadSpecial() {
  try {
    const data = await api("/api/v1/special/users");
    $("special-count").textContent = `(${data.users.length})`;
    if (!data.users.length) { $("special-list").innerHTML = '<div class="muted">还没有特别关注。点用户旁的 ☆ 添加。</div>'; return; }
    renderUsers($("special-list"), data.users, loadFollowing);
  } catch {}
}

// ── 时间线 + 评论 ───────────────────────────────────────────────
let timelineMode = "all";   // "all" 主时间线 | "special" 特别关注
$("reload-timeline").addEventListener("click", loadTimeline);
$("tl-mode-all").addEventListener("click", () => setTimelineMode("all"));
$("tl-mode-special").addEventListener("click", () => setTimelineMode("special"));
function setTimelineMode(mode) {
  timelineMode = mode;
  $("tl-mode-all").classList.toggle("active", mode === "all");
  $("tl-mode-special").classList.toggle("active", mode === "special");
  loadTimeline();
}
async function loadTimeline() {
  $("timeline-list").innerHTML = '<div class="muted">加载中…</div>';
  const path = timelineMode === "special" ? "/api/v1/special/timeline?limit=50" : "/api/v1/timeline?limit=50";
  try {
    const data = await api(path);
    if (timelineMode === "special" && !data.posts.length) {
      $("timeline-list").innerHTML = '<div class="muted">还没有特别关注的微博。在用户旁点 ☆ 添加特别关注。</div>';
      return;
    }
    renderPosts($("timeline-list"), data.posts);
  } catch (err) { $("timeline-list").innerHTML = `<div class="error">${esc(err.message)}</div>`; }
}
$("reload-hot").addEventListener("click", loadHot);
async function loadHot() {
  $("hot-list").innerHTML = '<div class="muted">加载中…</div>';
  try { const data = await api("/api/v1/hot?limit=50"); renderPosts($("hot-list"), data.posts); }
  catch (err) { $("hot-list").innerHTML = `<div class="error">${esc(err.message)}</div>`; }
}
function postInnerHtml(p, isRetweet) {
  const pics = (p.pics || []).map((u) => `<img src="${esc(img(u))}" loading="lazy"/>`).join("");
  const head = isRetweet ? "" :
    `<div class="post-head"><img src="${esc(img(p.user_avatar))}" class="clk" data-uid="${esc(p.user_id)}"/>
     <span class="pname clk" data-uid="${esc(p.user_id)}">${esc(p.user_name)}</span></div>`;
  const meta = isRetweet ? "" :
    `<div class="post-meta"><span>${esc(p.created_at)}</span><span>赞 ${p.likes_count}</span>
     <span class="clk-cmt" data-mid="${esc(p.id)}">评论 ${p.comments_count} ▾</span><span>转 ${p.reposts_count}</span></div>
     <div class="comments hidden" id="cm-${esc(p.id)}"></div>`;
  return `${head}<div class="post-text">${esc(p.text)}</div>
    ${pics ? `<div class="post-pics">${pics}</div>` : ""}
    ${p.retweet ? `<div class="post-retweet"><b class="clk" data-uid="${esc(p.retweet.user_id)}">@${esc(p.retweet.user_name)}</b>: ${postInnerHtml(p.retweet, true)}</div>` : ""}
    ${meta}`;
}
function renderPosts(container, posts) {
  if (!posts.length) { container.innerHTML = '<div class="muted">暂无微博</div>'; return; }
  container.innerHTML = posts.map((p) => `<div class="post">${postInnerHtml(p, false)}</div>`).join("");
  bindPosts(container);
}
function bindPosts(container) {
  container.querySelectorAll(".clk[data-uid]").forEach((el) =>
    el.addEventListener("click", () => el.dataset.uid && openProfile(el.dataset.uid)));
  container.querySelectorAll(".clk-cmt[data-mid]").forEach((el) =>
    el.addEventListener("click", () => toggleComments(el.dataset.mid)));
}
const cmtState = {};  // mid -> next_max_id
async function toggleComments(mid) {
  const box = $("cm-" + mid);
  if (!box) return;
  if (!box.classList.contains("hidden") && box.dataset.loaded) { box.classList.toggle("hidden"); return; }
  box.classList.remove("hidden");
  if (box.dataset.loaded) return;
  box.innerHTML = '<div class="muted small">加载评论…</div>';
  await loadComments(mid, box, true);
}
async function loadComments(mid, box, first) {
  try {
    const maxId = cmtState[mid] || "";
    const data = await api(`/api/v1/posts/${encodeURIComponent(mid)}/comments` + (maxId ? `?max_id=${encodeURIComponent(maxId)}` : ""));
    if (first) box.innerHTML = "";
    const list = document.createElement("div");
    list.innerHTML = data.comments.map((c) => `
      <div class="comment">
        <img src="${esc(img(c.user_avatar))}" class="clk" data-uid=""/>
        <div><span class="cm-name">${esc(c.user_name)}</span>
        <span class="cm-text">${esc(c.text)}</span>
        <span class="muted small">赞 ${c.like_count}</span></div>
      </div>`).join("") || '<div class="muted small">暂无评论</div>';
    box.appendChild(list);
    box.dataset.loaded = "1";
    cmtState[mid] = data.next_max_id || "";
    let more = box.querySelector(".cm-more");
    if (more) more.remove();
    if (data.next_max_id) {
      more = document.createElement("button");
      more.className = "ghost small-btn cm-more";
      more.textContent = "加载更多评论";
      more.addEventListener("click", () => { more.disabled = true; loadComments(mid, box, false); });
      box.appendChild(more);
    }
  } catch (err) { box.innerHTML += `<div class="error small">${esc(err.message)}</div>`; }
}

// ── 用户主页弹窗 ────────────────────────────────────────────────
let profileUid = null;
function openProfile(uid) {
  if (!uid) return;
  profileUid = uid;
  $("profile-modal").classList.remove("hidden");
  $("profile-header").innerHTML = '<div class="muted">加载中…</div>';
  $("profile-body").innerHTML = "";
  document.querySelectorAll(".ptab").forEach((t, i) => t.classList.toggle("active", i === 0));
  loadProfileHeader(uid);
  loadProfileTab("posts");
}
$("profile-close").addEventListener("click", () => $("profile-modal").classList.add("hidden"));
document.querySelectorAll(".ptab").forEach((t) =>
  t.addEventListener("click", () => {
    document.querySelectorAll(".ptab").forEach((x) => x.classList.remove("active"));
    t.classList.add("active");
    loadProfileTab(t.dataset.ptab);
  }));

async function loadProfileHeader(uid) {
  try {
    const u = await api("/api/v1/users/" + encodeURIComponent(uid));
    const btn = u.followed
      ? `<button class="ghost small-btn" id="pf-follow">取关</button>`
      : `<button class="small-btn" id="pf-follow">关注</button>`;
    const sbtn = u.special
      ? `<button class="star-btn active" id="pf-special" title="取消特别关注">★</button>`
      : `<button class="star-btn" id="pf-special" title="特别关注">☆</button>`;
    $("profile-header").innerHTML = `
      ${u.cover_url ? `<div class="pf-cover" style="background-image:url('${esc(img(u.cover_url))}')"></div>` : ""}
      <div class="pf-top">
        <img class="pf-avatar" src="${esc(img(u.avatar_url))}"/>
        <div class="pf-meta">
          <div class="pf-name">${esc(u.screen_name)} ${u.verified ? "✔" : ""}</div>
          <div class="muted small">${esc(u.verified_reason || "")}</div>
          <div class="pf-stats muted small">微博 ${u.statuses_count} · 关注 ${u.follow_count} · 粉丝 ${esc(String(u.followers_count))}</div>
        </div>${sbtn}${btn}
      </div>
      <div class="pf-desc">${esc(u.description || "")}</div>`;
    $("pf-follow").addEventListener("click", async () => {
      $("pf-follow").disabled = true;
      if (u.followed) await doUnfollow(uid); else await doFollow(uid);
      loadProfileHeader(uid); loadFollowing();
    });
    $("pf-special").addEventListener("click", async () => {
      $("pf-special").disabled = true;
      if (u.special) await doUnspecial(uid); else await doSpecial(uid);
      loadProfileHeader(uid); loadFollowing();
    });
  } catch (err) { $("profile-header").innerHTML = `<div class="error">${esc(err.message)}</div>`; }
}
async function loadProfileTab(which) {
  const body = $("profile-body");
  body.innerHTML = '<div class="muted">加载中…</div>';
  try {
    if (which === "posts") {
      const d = await api(`/api/v1/users/${encodeURIComponent(profileUid)}/posts?count=20`);
      const feed = document.createElement("div"); feed.className = "feed";
      body.innerHTML = ""; body.appendChild(feed);
      renderPosts(feed, d.posts);
    } else {
      const d = await api(`/api/v1/users/${encodeURIComponent(profileUid)}/following?page=2`);
      const list = document.createElement("div"); list.className = "user-list";
      body.innerHTML = ""; body.appendChild(list);
      renderUsers(list, d.users, () => loadProfileTab("following"));
    }
  } catch (err) { body.innerHTML = `<div class="error">${esc(err.message)}</div>`; }
}

// ── 设置 ────────────────────────────────────────────────────────
const SETTING_FIELDS = ["cookie", "round_interval_sec", "req_delay_min_sec", "req_delay_max_sec",
  "posts_per_user", "min_check_interval_sec", "post_retention_days", "max_cached_posts",
  "hot_count", "max_cached_hot", "hot_containerid",
  "webdav_url", "webdav_user", "webdav_pass"];
async function loadSettings() {
  try {
    const s = await api("/admin/api/settings");
    SETTING_FIELDS.forEach((k) => { const el = $("set-" + k); if (el) el.value = s[k] || ""; });
    $("set-scrape_enabled").checked = (s.scrape_enabled || "").toString() === "true";
    $("set-hot_enabled").checked = (s.hot_enabled || "").toString() === "true";
  } catch {}
}
$("settings-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const payload = {};
  SETTING_FIELDS.forEach((k) => { const el = $("set-" + k); if (el) payload[k] = el.value; });
  payload.scrape_enabled = $("set-scrape_enabled").checked ? "true" : "false";
  payload.hot_enabled = $("set-hot_enabled").checked ? "true" : "false";
  try {
    await api("/admin/api/settings", { method: "POST", body: JSON.stringify(payload) });
    $("settings-msg").textContent = "已保存 ✓"; setTimeout(() => ($("settings-msg").textContent = ""), 2000);
  } catch (err) { $("settings-msg").textContent = err.message; }
});

// ── API Token 管理 ──────────────────────────────────────────────
async function loadTokens() {
  try {
    const d = await api("/admin/api/tokens");
    const box = $("token-list");
    if (!d.tokens.length) { box.innerHTML = '<div class="muted small">还没有 Token，点下方「新建 Token」生成一个。</div>'; return; }
    box.innerHTML = d.tokens.map((t) => `
      <div class="token-item">
        <div class="token-main">
          <div class="token-label">${esc(t.label)}</div>
          <code class="token-value">${esc(t.token)}</code>
        </div>
        <button type="button" class="ghost small-btn" data-copy="${esc(t.token)}">复制</button>
        <button type="button" class="ghost small-btn" data-deltoken="${esc(t.id)}">删除</button>
      </div>`).join("");
    box.querySelectorAll("[data-copy]").forEach((b) =>
      b.addEventListener("click", async () => {
        try { await navigator.clipboard.writeText(b.dataset.copy); b.textContent = "已复制"; setTimeout(() => (b.textContent = "复制"), 1500); }
        catch { b.textContent = "复制失败"; }
      }));
    box.querySelectorAll("[data-deltoken]").forEach((b) =>
      b.addEventListener("click", async () => {
        if (!confirm("删除该 Token？使用它的 app 将立即无法连接。")) return;
        await api("/admin/api/tokens/" + encodeURIComponent(b.dataset.deltoken), { method: "DELETE" });
        loadTokens();
      }));
  } catch {}
}
$("token-create").addEventListener("click", async () => {
  const label = $("token-label").value.trim();
  try {
    await api("/admin/api/tokens", { method: "POST", body: JSON.stringify({ label }) });
    $("token-label").value = "";
    loadTokens();
  } catch (err) { alert("新建失败：" + err.message); }
});

// ── WebDAV ──────────────────────────────────────────────────────
$("webdav-backup").addEventListener("click", async () => {
  $("webdav-msg").textContent = "备份中…";
  try { await api("/admin/api/webdav/backup", { method: "POST" }); $("webdav-msg").textContent = "备份成功 ✓"; }
  catch (err) { $("webdav-msg").textContent = "失败：" + err.message; }
});
$("webdav-restore").addEventListener("click", async () => {
  $("webdav-msg").textContent = "恢复中…";
  const ic = $("restore-cookie").checked;
  try {
    const r = await api("/admin/api/webdav/restore?import_cookie=" + ic, { method: "POST" });
    $("webdav-msg").textContent = `恢复成功：导入 ${r.imported_users} 人（新增 ${r.added}）${r.cookie_imported ? "，含 Cookie" : ""}`;
    loadFollowing(); loadSettings();
  } catch (err) { $("webdav-msg").textContent = "失败：" + err.message; }
});

// ── 验证码弹窗 ──────────────────────────────────────────────────
let captchaWs = null;
const canvas = $("captcha-canvas");
const ctx = canvas.getContext("2d");
let dragging = false;
let lastPos = { x: 0, y: 0 };
function mapCoord(e) {
  const rect = canvas.getBoundingClientRect();
  const t = (e.touches && e.touches[0]) || (e.changedTouches && e.changedTouches[0]) || e;
  lastPos = { x: (t.clientX - rect.left) * (canvas.width / rect.width), y: (t.clientY - rect.top) * (canvas.height / rect.height) };
  return lastPos;
}
function wsSend(obj) { if (captchaWs && captchaWs.readyState === 1) captchaWs.send(JSON.stringify(obj)); }
function openCaptcha() {
  $("captcha-modal").classList.remove("hidden");
  $("captcha-loading").style.display = "flex";
  const proto = location.protocol === "https:" ? "wss" : "ws";
  captchaWs = new WebSocket(`${proto}://${location.host}/admin/api/captcha/ws`);
  captchaWs.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.type === "frame") {
      $("captcha-loading").style.display = "none";
      const im = new Image();
      im.onload = () => ctx.drawImage(im, 0, 0, canvas.width, canvas.height);
      im.src = "data:image/jpeg;base64," + msg.data;
      $("captcha-url").textContent = msg.url || "";
    } else if (msg.type === "state" && !msg.pending) {
      $("captcha-loading").textContent = "无待处理验证码"; $("captcha-loading").style.display = "flex";
    }
  };
}
function closeCaptcha() { $("captcha-modal").classList.add("hidden"); if (captchaWs) { captchaWs.close(); captchaWs = null; } }
$("open-captcha").addEventListener("click", openCaptcha);
$("captcha-close").addEventListener("click", closeCaptcha);
$("captcha-resolve").addEventListener("click", () => { wsSend({ type: "resolve" }); closeCaptcha(); });
$("captcha-cancel").addEventListener("click", () => { wsSend({ type: "cancel" }); closeCaptcha(); });
$("captcha-reload").addEventListener("click", () => wsSend({ type: "reload" }));
canvas.addEventListener("mousedown", (e) => { dragging = true; const p = mapCoord(e); wsSend({ type: "down", ...p }); });
canvas.addEventListener("mousemove", (e) => { if (dragging) { const p = mapCoord(e); wsSend({ type: "move", ...p }); } });
window.addEventListener("mouseup", (e) => { if (dragging) { dragging = false; const p = mapCoord(e); wsSend({ type: "up", ...p }); } });
canvas.addEventListener("touchstart", (e) => { e.preventDefault(); dragging = true; const p = mapCoord(e); wsSend({ type: "down", ...p }); });
canvas.addEventListener("touchmove", (e) => { e.preventDefault(); if (dragging) { const p = mapCoord(e); wsSend({ type: "move", ...p }); } });
canvas.addEventListener("touchend", (e) => { e.preventDefault(); if (dragging) { dragging = false; mapCoord(e); wsSend({ type: "up", ...lastPos }); } });

// ── 启动 ────────────────────────────────────────────────────────
(async function init() {
  try { await api("/admin/api/session"); showMain(); } catch { showLogin(); }
})();
