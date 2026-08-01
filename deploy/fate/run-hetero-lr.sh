#!/usr/bin/env bash
# M3-S0：FATE 1.11 standalone 内跑通纵向逻辑回归官方样例（breast 数据，guest 9999 / host 10000）。
# 步骤：上传双方数据 → 提交 hetero_lr 任务 → 轮询至终态 → 摘取评估指标。
# 用法：./run-hetero-lr.sh [容器名]
set -euo pipefail

NAME="${1:-fate-s0}"
FATE_HOME=/data/projects/fate

in_fate() {
  docker exec "$NAME" bash -lc "source $FATE_HOME/bin/init_env.sh && $*"
}

echo "== 1. 生成上传配置并上传双方数据 =="
docker exec "$NAME" bash -lc "cat > /tmp/upload_guest.json <<'EOF'
{\"file\": \"$FATE_HOME/examples/data/breast_hetero_guest.csv\",
 \"head\": 1, \"partition\": 4,
 \"namespace\": \"experiment\", \"table_name\": \"breast_hetero_guest\"}
EOF
cat > /tmp/upload_host.json <<'EOF'
{\"file\": \"$FATE_HOME/examples/data/breast_hetero_host.csv\",
 \"head\": 1, \"partition\": 4,
 \"namespace\": \"experiment\", \"table_name\": \"breast_hetero_host\"}
EOF"
in_fate "flow data upload -c /tmp/upload_guest.json --drop" | tail -3
in_fate "flow data upload -c /tmp/upload_host.json --drop" | tail -3

echo "== 2. 定位官方 hetero_lr 样例并提交 =="
DSL=$(in_fate "ls $FATE_HOME/examples/dsl/v2/hetero_logistic_regression/*normal*dsl*.json | head -1" | tr -d '\r')
CONF=$(in_fate "ls $FATE_HOME/examples/dsl/v2/hetero_logistic_regression/*normal*conf*.json | head -1" | tr -d '\r')
echo "DSL=$DSL"
echo "CONF=$CONF"
SUBMIT=$(in_fate "flow job submit -c $CONF -d $DSL")
echo "$SUBMIT" | tail -5
JOB_ID=$(echo "$SUBMIT" | python3 -c "import json,sys,re; s=sys.stdin.read(); print(json.loads(s[s.index('{'):])['jobId'])")
echo "JOB_ID=$JOB_ID"

echo "== 3. 轮询任务状态（超时 30 分钟） =="
for i in $(seq 1 180); do
  STATUS=$(in_fate "flow job query -j $JOB_ID" | python3 -c "import json,sys; s=sys.stdin.read(); d=json.loads(s[s.index('{'):]); print(d['data'][0]['f_status'] if d.get('data') else 'unknown')" 2>/dev/null || echo probing)
  echo "  [$i] $STATUS"
  case "$STATUS" in
    success) break ;;
    failed|canceled) echo "任务失败：flow job log -j $JOB_ID 查详情" >&2; exit 1 ;;
  esac
  sleep 10
done
[ "$STATUS" = "success" ] || { echo "超时未完成" >&2; exit 1; }

echo "== 4. 训练指标（guest 侧 hetero_lr_0 概要） =="
in_fate "flow component metric-all -j $JOB_ID -r guest -p 9999 -cpn hetero_lr_0" | head -40
echo "== 完成：训练链路验证通过（job $JOB_ID） =="
