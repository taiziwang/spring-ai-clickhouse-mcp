#!/usr/bin/env bash
# 公共函数，被 bin/ 下其它脚本 source。不要单独执行。
#
# 提供：JDK 定位与校验、jar 存在性检查、端口/进程查询、优雅停止。
# 注意：这里刻意【不写】set -e —— 它是被 source 的，设了会影响调用方的行为。

REQUIRED_JDK=17
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$PROJECT_DIR/target/spring-ai-clickhouse-mcp.jar"
RUNDIR="$PROJECT_DIR/.run"

# 定位 JDK 17+（优先 17，其次 21 / 25），并导出 JAVA_HOME / PATH。
# 为什么需要：MCP 客户端、CI、systemd 都以「非交互进程」方式拉起命令，
# 而 SDKMAN 只在交互式 shell 里初始化 —— 那些场景下 PATH 上根本没有 java。
locate_jdk() {
  if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in "$HOME"/.sdkman/candidates/java/17* \
                     "$HOME"/.sdkman/candidates/java/21* \
                     "$HOME"/.sdkman/candidates/java/25*; do
      if [ -x "$candidate/bin/java" ]; then
        export JAVA_HOME="$candidate"
        break
      fi
    done
  fi
  if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "没找到 JDK。请把 JAVA_HOME 指向 JDK ${REQUIRED_JDK}+。" >&2
    return 1
  fi
  export PATH="$JAVA_HOME/bin:$PATH"

  local major
  major="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
  if [ -z "$major" ] || [ "$major" -lt "$REQUIRED_JDK" ]; then
    echo "JAVA_HOME 指向 JDK ${major:-未知}，本项目要求 JDK ${REQUIRED_JDK}+。" >&2
    return 1
  fi
  return 0
}

require_jar() {
  if [ ! -f "$JAR" ]; then
    echo "没找到 $JAR，先执行： bin/build.sh clean package" >&2
    return 1
  fi
  return 0
}

# 打印监听某端口的进程 PID；没有则输出空串。
port_pid() {
  local port="$1" pid=""
  if command -v ss >/dev/null 2>&1; then
    pid="$(ss -lntpH 2>/dev/null | grep -F ":$port " \
           | sed -n 's/.*pid=\([0-9]\+\).*/\1/p' | head -1)"
  fi
  if [ -z "$pid" ] && command -v fuser >/dev/null 2>&1; then
    pid="$(fuser -n tcp "$port" 2>/dev/null | tr -s ' ' '\n' | grep -E '^[0-9]+$' | head -1)"
  fi
  printf '%s' "$pid"
}

pid_alive() {
  kill -0 "$1" 2>/dev/null
}

# 优雅停止一个 PID：先 SIGCONT，再 SIGTERM，超时后 SIGKILL。
# ⚠️ 必须先 SIGCONT：被 Ctrl+Z 挂起的进程不运行，SIGTERM 只会排队等着。
stop_pid() {
  local pid="$1" timeout="${2:-15}" i=0
  pid_alive "$pid" || return 0

  local stat
  stat="$(ps -o stat= -p "$pid" 2>/dev/null || true)"
  case "$stat" in
    *T*)
      kill -CONT "$pid" 2>/dev/null
      sleep 1
      ;;
  esac
  kill -TERM "$pid" 2>/dev/null

  while [ "$i" -lt "$timeout" ]; do
    pid_alive "$pid" || return 0
    sleep 1
    i=$((i + 1))
  done

  echo "  进程 $pid 在 ${timeout}s 内未响应 SIGTERM，改用 SIGKILL" >&2
  kill -9 "$pid" 2>/dev/null
  sleep 1
  pid_alive "$pid" && return 1
  return 0
}
