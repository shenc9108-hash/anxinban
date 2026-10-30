#!/bin/bash
# 安心伴后端部署脚本（Debian / Ubuntu，root 运行）
#
# 用法：
#   bash deploy.sh [域名] [家长密钥] [端口]
#
# 示例：
#   bash deploy.sh shouhu.966099.xyz caishen999 3100
#
# 特性：
#   - 重复执行时优先复用当前端口，不会因为端口变动导致反代断裂
#   - 自动挑选空闲端口，不硬编码 3000
#   - 检测到 80/443 已被占用时，不改动 Caddy/nginx，只打印反代配置提示
#   - 已存在的配置会先备份，失败自动回滚
#   - 密钥沿用已有配置，不会随机变更导致家长端失效
#   - 保留运行数据 db.json

set -euo pipefail

DOMAIN="${1:-shouhu.966099.xyz}"
KEY="${2:-}"
PREFER_PORT="${3:-3100}"

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST=/opt/anxinban
UNIT=/etc/systemd/system/anxinban.service
STAMP="$(date +%Y%m%d%H%M%S)"
BACKUP="/opt/anxinban-backup-$STAMP"

info() { echo -e "\033[32m[OK]\033[0m $*"; }
warn() { echo -e "\033[33m[!]\033[0m $*"; }
die()  { echo -e "\033[31m[FAIL]\033[0m $*" >&2; exit 1; }

# ---------- 0. 前置检查 ----------
[ "$(id -u)" -eq 0 ] || die "请用 root 运行"

CUR_PORT=""
CUR_KEY=""
if [ -f "$UNIT" ]; then
  CUR_PORT="$(grep '^Environment=PORT=' "$UNIT" 2>/dev/null | head -1 | cut -d= -f3- || true)"
  CUR_KEY="$(grep '^Environment=PARENT_KEY=' "$UNIT" 2>/dev/null | head -1 | cut -d= -f3- || true)"
fi

if [ -z "$KEY" ]; then
  [ -n "$CUR_KEY" ] || die "首次部署必须提供家长密钥，例如：bash deploy.sh $DOMAIN 你的强密钥"
  KEY="$CUR_KEY"
  warn "未提供密钥，沿用现有密钥"
fi

[ ${#KEY} -ge 8 ] || warn "密钥少于 8 位，容易被猜到，建议换更长的"

# ---------- 1. 选择端口（关键：优先复用自己正在用的端口） ----------
port_listening() { ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"; }

PORT=""

# 1.1 已有配置且该端口仍在监听（就是本服务）→ 直接复用
if [ -n "$CUR_PORT" ] && port_listening "$CUR_PORT"; then
  PORT="$CUR_PORT"
  info "复用现有端口：$PORT"
fi

# 1.2 已有配置但端口未监听 → 先停掉旧服务再说，避免它自己占着端口被误判
if [ -z "$PORT" ] && [ -n "$CUR_PORT" ]; then
  PORT="$CUR_PORT"
  info "沿用现有配置端口：$PORT"
fi

# 1.3 全新部署 → 从首选端口往后找一个空闲的
if [ -z "$PORT" ]; then
  for i in $(seq 0 20); do
    p=$((PREFER_PORT + i))
    if ! port_listening "$p"; then PORT="$p"; break; fi
  done
fi

[ -n "$PORT" ] || die "找不到可用端口（尝试过 $PREFER_PORT 起的 21 个）"
info "使用端口：$PORT"

# ---------- 2. 备份 ----------
mkdir -p "$BACKUP"
[ -d "$DEST" ] && cp -a "$DEST"/. "$BACKUP"/ 2>/dev/null || true
[ -f "$UNIT" ] && cp -a "$UNIT" "$BACKUP"/anxinban.service 2>/dev/null && info "旧配置已备份到 $BACKUP"

rollback() {
  warn "部署失败，正在回滚..."
  if [ -f "$BACKUP/anxinban.service" ]; then
    cp -a "$BACKUP/anxinban.service" "$UNIT"
    systemctl daemon-reload
    systemctl restart anxinban 2>/dev/null || true
    warn "服务配置已恢复"
  fi
}
trap 'rollback' ERR

# ---------- 3. 依赖 ----------
need_apt=0
for pkg in curl gpg ca-certificates debian-keyring debian-archive-keyring apt-transport-https; do
  dpkg -s "$pkg" >/dev/null 2>&1 || need_apt=1
done
if [ "$need_apt" -eq 1 ]; then
  info "安装系统依赖..."
  apt-get update -y
  apt-get install -y curl gpg ca-certificates debian-keyring debian-archive-keyring apt-transport-https
else
  info "系统依赖已齐全，跳过 apt-get update"
fi

if ! command -v node >/dev/null 2>&1; then
  info "安装 Node.js 20..."
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi
NODE_MAJOR="$(node -v | sed 's/^v//' | cut -d. -f1)"
[ "$NODE_MAJOR" -ge 18 ] || die "Node 版本过低（当前 $(node -v)），需要 18+"
info "Node 版本：$(node -v)"

# ---------- 4. 部署程序（保留 db.json） ----------
mkdir -p "$DEST"
for f in server.js package.json; do
  [ -f "$SRC/$f" ] && cp -a "$SRC/$f" "$DEST/$f"
done
if [ -d "$SRC/public" ]; then
  mkdir -p "$DEST/public"
  cp -a "$SRC"/public/. "$DEST/public/"
fi
[ -f "$DEST/db.json" ] || echo '{"devices":{},"codes":{},"requests":[],"alerts":[]}' > "$DEST/db.json"
info "程序文件已复制（db.json 保留）"

# ---------- 5. systemd ----------
cat > "$UNIT" <<UNIT
[Unit]
Description=Anxinban server
After=network.target

[Service]
WorkingDirectory=$DEST
Environment=PORT=$PORT
Environment=PARENT_KEY=$KEY
ExecStart=/usr/bin/node server.js
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable anxinban >/dev/null 2>&1
systemctl reset-failed anxinban 2>/dev/null || true
systemctl restart anxinban
sleep 2
systemctl is-active --quiet anxinban || die "服务启动失败，查看：journalctl -u anxinban -n 30"

for i in $(seq 1 10); do
  curl -fsS "http://127.0.0.1:$PORT/" >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS "http://127.0.0.1:$PORT/" >/dev/null 2>&1 || die "后端未响应，查看：journalctl -u anxinban -n 30"
info "后端已启动并响应：127.0.0.1:$PORT"

trap - ERR

# ---------- 6. 反向代理 / HTTPS ----------
port_busy() { ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"; }

if port_busy 80 || port_busy 443; then
  echo
  warn "检测到 80/443 已被占用（已有 nginx / 宝塔 / 其他 web 服务）。"
  warn "为不影响现有站点，本脚本【不会】安装或改动 Caddy / nginx。"
  echo
  echo "  请确认反向代理指向：127.0.0.1:$PORT"
  echo
  echo "  ── nginx（宝塔：网站 → 设置 → 反向代理）──"
  echo "      location / {"
  echo "          proxy_pass http://127.0.0.1:$PORT;"
  echo "          proxy_set_header Host \$host;"
  echo "          proxy_set_header X-Real-IP \$remote_addr;"
  echo "          proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;"
  echo "      }"
  echo
  echo "  ── Caddy（/etc/caddy/Caddyfile）──"
  echo "      $DOMAIN {"
  echo "          reverse_proxy 127.0.0.1:$PORT"
  echo "      }"
  echo

  # 检查现有反代是否指向本端口，给出明确结论
  if grep -rqE "127\.0\.0\.1:$PORT" /www/server/panel/vhost/nginx/proxy/*/*.conf /etc/nginx /etc/caddy 2>/dev/null; then
    info "已检测到现有反代指向 $PORT，无需改动"
  else
    warn "未检测到指向 $PORT 的反代配置，请按上面示例手动添加"
  fi

elif ! command -v caddy >/dev/null 2>&1; then
  info "80/443 空闲，安装 Caddy 自动配置 HTTPS..."
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
    | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
    > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y
  apt-get install -y caddy

  [ -f /etc/caddy/Caddyfile ] && cp -a /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.bak.$STAMP"
  cat > /etc/caddy/Caddyfile <<CADDY
$DOMAIN {
    reverse_proxy 127.0.0.1:$PORT
}
CADDY
  systemctl enable caddy >/dev/null 2>&1
  systemctl restart caddy
  sleep 2
  systemctl is-active --quiet caddy && info "Caddy 已启动" || warn "Caddy 未正常启动，检查 journalctl -u caddy"
else
  info "Caddy 已安装，跳过（不覆盖现有配置）"
fi

# ---------- 7. 防火墙 ----------
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow 80/tcp  >/dev/null 2>&1 || true
  ufw allow 443/tcp >/dev/null 2>&1 || true
  info "ufw 已放行 80/443"
fi

# ---------- 8. 结果 ----------
echo
echo "================================================"
echo " 部署完成"
echo "================================================"
echo " 家长端地址：https://$DOMAIN"
echo " 家长密钥：  $KEY"
echo " 孩子端填写：$DOMAIN"
echo " 后端端口：  $PORT （仅本机监听，由反代转发）"
echo " 备份位置：  $BACKUP"
echo
echo " 常用命令："
echo "   查看状态： systemctl status anxinban"
echo "   查看日志： journalctl -u anxinban -f"
echo "   重启服务： systemctl restart anxinban"
echo "================================================"
