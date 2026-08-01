#!/usr/bin/env bash
# M3-S0：FATE 1.11.3 standalone 起立（开发态，单容器内含 guest 9999 / host 10000 双方模拟）。
# Apple Silicon 注意：镜像为 amd64，经 Rosetta 仿真运行；生产按 x86 服务器部署。
# 用法：./start-standalone.sh [容器名] [fateboard端口]
set -euo pipefail

NAME="${1:-fate-s0}"
BOARD_PORT="${2:-8080}"

docker rm -f "$NAME" 2>/dev/null || true
# 镜像 ENTRYPOINT 启动服务后 exec CMD（默认交互 bash，无 TTY 即退出）——覆盖为常驻命令保活
docker run -d --name "$NAME" \
  --platform linux/amd64 \
  -p "$BOARD_PORT:8080" \
  federatedai/standalone_fate:1.11.3 \
  bash -c "sleep infinity"

echo "等待 FATE Flow 就绪（首次启动约 1-3 分钟）…"
for i in $(seq 1 60); do
  if docker exec "$NAME" bash -c "curl -s -m 5 -X POST http://localhost:9380/v1/version/get -H 'Content-Type: application/json' -d '{}'" 2>/dev/null | grep -q '"retcode":0'; then
    echo "FATE Flow 就绪。FATEBoard: http://localhost:$BOARD_PORT"
    exit 0
  fi
  sleep 5
done
echo "FATE Flow 未在预期时间就绪，查看日志：docker logs $NAME" >&2
exit 1
