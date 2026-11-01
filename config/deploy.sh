#!/usr/bin/env bash
# 用法（在服务器上以 root 执行）：
#   1. 把三个文件放到同一个目录（例如 /root/deploy）：
#        app.jar              打包出来的 yu-picture-backend-DDD-0.0.1-SNAPSHOT.jar，改名为 app.jar
#        env                  config/env.local，改名为 env
#        yu-picture.service   config/yu-picture.service（仅旧的手动部署模式需要）
#   2. bash deploy.sh
# 重复执行会覆盖旧 jar 并重启服务，可以当升级脚本用。
#
# 后端现在由宝塔「Java项目」管理（服务名 spring_yu-picture），
# 脚本会自动识别：有宝塔服务就只重启它，不再安装/启用旧的 yu-picture.service。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP_DIR=/opt/yu-picture
CONF_DIR=/etc/yu-picture
BT_SERVICE=spring_yu-picture
OLD_UNIT_FILE=/etc/systemd/system/yu-picture.service

[ "$(id -u)" -eq 0 ] || { echo "请用 root 执行"; exit 1; }
for f in app.jar env; do
  [ -f "$HERE/$f" ] || { echo "缺少文件: $HERE/$f"; exit 1; }
done

BT_MANAGED=0
if systemctl list-unit-files "$BT_SERVICE.service" >/dev/null 2>&1 \
   && systemctl list-unit-files "$BT_SERVICE.service" | grep -q "^$BT_SERVICE.service"; then
  BT_MANAGED=1
fi
if [ "$BT_MANAGED" -eq 0 ] && [ ! -f "$HERE/yu-picture.service" ]; then
  echo "缺少文件: $HERE/yu-picture.service（当前不是宝塔管理模式时需要）"; exit 1
fi

# 优先用 PATH 里的 java；没有就找宝塔自带的 JDK（优先 17）
JAVA_BIN="$(command -v java || true)"
if [ -z "$JAVA_BIN" ]; then
  for c in /www/server/java/jdk-17*/bin/java /www/server/java/jdk-21*/bin/java /www/server/java/jdk-11*/bin/java /usr/lib/jvm/*/bin/java; do
    [ -x "$c" ] && JAVA_BIN="$c" && break
  done
fi
[ -n "$JAVA_BIN" ] || { echo "服务器没装 Java，请先安装 JDK 11+"; exit 1; }
echo "使用 Java: $JAVA_BIN"

# 1. 运行账号：无登录权限的系统用户
if ! id yu-picture >/dev/null 2>&1; then
  useradd --system --user-group --no-create-home --shell /sbin/nologin yu-picture
fi

# 2. 目录
install -d -o yu-picture -g yu-picture -m 755 "$APP_DIR"          # 程序目录
install -d -o root       -g root       -m 755 "$CONF_DIR"          # 配置目录

# 3. 程序（只读即可）
install -o yu-picture -g yu-picture -m 644 "$HERE/app.jar" "$APP_DIR/app.jar"

# 4. 环境变量：去 Windows 换行；root 所有、yu-picture 组可读
sed 's/\r$//' "$HERE/env" > "$CONF_DIR/env"
chown root:yu-picture "$CONF_DIR/env"
chmod 640 "$CONF_DIR/env"

if [ "$BT_MANAGED" -eq 1 ]; then
  # 宝塔 Java 项目模式：服务由宝塔生成，只重启它
  echo "检测到宝塔 Java 项目服务: $BT_SERVICE"
  if [ -f "$OLD_UNIT_FILE" ]; then
    # 防止旧的 systemd 服务也开机自启，造成 8123 端口冲突
    systemctl disable yu-picture >/dev/null 2>&1 || true
    systemctl stop yu-picture >/dev/null 2>&1 || true
  fi
  systemctl restart "$BT_SERVICE"
  SERVICE_NAME="$BT_SERVICE"
  LOG_FILE=/www/wwwlogs/java/springboot/yu-picture.log
else
  # 旧的手动 systemd 模式
  sed 's/\r$//' "$HERE/yu-picture.service" > "$OLD_UNIT_FILE"
  sed -i "s|^ExecStart=[^ ]*java|ExecStart=$JAVA_BIN|" "$OLD_UNIT_FILE"
  chmod 644 "$OLD_UNIT_FILE"
  systemctl daemon-reload
  systemctl enable yu-picture >/dev/null 2>&1 || true
  systemctl restart yu-picture
  SERVICE_NAME=yu-picture
  LOG_FILE=""
fi

echo "等待启动..."
for i in $(seq 1 20); do
  if ! systemctl is-active --quiet "$SERVICE_NAME"; then
    echo "服务已退出，最近日志："
    if [ -n "$LOG_FILE" ] && [ -f "$LOG_FILE" ]; then
      tail -n 100 "$LOG_FILE"
    else
      journalctl -u "$SERVICE_NAME" -n 100 --no-pager
    fi
    exit 1
  fi
  if ss -ltn 2>/dev/null | grep -q ':8123 '; then
    echo "启动成功：8123 已在监听（服务 $SERVICE_NAME）"
    if [ -n "$LOG_FILE" ]; then
      echo "日志：$LOG_FILE"
    else
      echo "日志：journalctl -u $SERVICE_NAME -f"
    fi
    exit 0
  fi
  sleep 3
done

echo "服务在跑但 8123 还没监听，看日志："
if [ -n "$LOG_FILE" ] && [ -f "$LOG_FILE" ]; then
  tail -n 100 "$LOG_FILE"
else
  journalctl -u "$SERVICE_NAME" -n 100 --no-pager
fi
