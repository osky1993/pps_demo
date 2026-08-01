#!/usr/bin/env bash
# 模型发布到 FATE-Serving（M3 B1 解决方案固化）：deploy → load → bind → 推理自检。
# 用法：./publish-model.sh <训练jobId> [serviceId] [fate容器] [serving容器]
#
# 三个必踩的坑（B1 根因，均已在此脚本内处理）：
#   1. 训练产出的模型必须先 `model deploy` 生成**可部署版本**，直接 load 原始版本报
#      "Only deployed models could be used to execute process of loading"；
#   2. service_conf.yaml 的 servings 必须指向 **serving-server:8000**（gRPC ModelService），
#      指向 serving-proxy:8059(HTTP) 报 UNAVAILABLE，指向 proxy:8879(gRPC) 报
#      "Method not found: ModelService/publishLoad"；
#   3. fateflow.host 必须是**容器真实 IP**（见 start-standalone.sh 注释）。
set -euo pipefail

JOB_ID="${1:?用法: ./publish-model.sh <训练jobId> [serviceId] [fate容器] [serving容器]}"
SERVICE_ID="${2:-ppc-risk-model}"
FATE="${3:-fate-s0}"
SERVING="${4:-serving-s0}"

in_fate() { docker exec "$FATE" bash -lc "source /data/projects/fate/bin/init_env.sh && $*"; }
# json_field 在**宿主**执行（容器内无 python3）
json_field() { python3 -c "import json,sys; s=sys.stdin.read(); print(json.loads(s[s.index('{'):])$1)"; }

echo "== 0. 接线检查：servings 指向 serving-server gRPC =="
SERVING_IP=$(docker inspect "$SERVING" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
# 容器内无 python3；用 awk 定位 servings.hosts 首个列表项并改写
docker exec "$FATE" bash -c "awk -v ip='${SERVING_IP}:8000' '
  /^servings:/ {inservings=1}
  inservings && /^[a-z]/ && !/^servings:/ {inservings=0}
  inservings && /^ *- / {print \"    - \" ip; next}
  {print}' /data/projects/fate/conf/service_conf.yaml > /tmp/sc.yaml &&
  mv /tmp/sc.yaml /data/projects/fate/conf/service_conf.yaml &&
  grep -A2 '^servings:' /data/projects/fate/conf/service_conf.yaml"
in_fate "cd /data/projects/fate/fateflow && bash bin/service.sh restart" >/dev/null 2>&1
sleep 15

echo "== 1. 取训练任务的模型坐标 =="
in_fate "flow job config -j $JOB_ID -r guest -p 9999 -o /tmp/mc_$JOB_ID" >/dev/null 2>&1
MODEL_ID=$(docker exec "$FATE" cat "/tmp/mc_$JOB_ID/job_${JOB_ID}_config/model_info.json" | json_field "['model_id']")
MODEL_VERSION=$(docker exec "$FATE" cat "/tmp/mc_$JOB_ID/job_${JOB_ID}_config/model_info.json" | json_field "['model_version']")
echo "  model_id=$MODEL_ID  version=$MODEL_VERSION"

echo "== 2. deploy（生成可部署版本，load 的前提） =="
DEPLOYED=$(in_fate "flow model deploy --model-id '$MODEL_ID' --model-version '$MODEL_VERSION'" \
  | json_field "['data']['model_version']")
echo "  deployed_version=$DEPLOYED"

echo "== 3. load（下发 Serving） =="
docker exec "$FATE" bash -c "cat > /tmp/publish_load.json <<EOF
{\"initiator\": {\"party_id\": \"9999\", \"role\": \"guest\"},
 \"role\": {\"guest\": [\"9999\"], \"host\": [\"10000\"], \"arbiter\": [\"10000\"]},
 \"job_parameters\": {\"model_id\": \"$MODEL_ID\", \"model_version\": \"$DEPLOYED\"}}
EOF"
LOAD_RC=$(in_fate "flow model load -c /tmp/publish_load.json" | json_field "['retcode']")
[ "$LOAD_RC" = "0" ] || { echo "load 失败 retcode=$LOAD_RC" >&2; exit 1; }
echo "  load 成功"

echo "== 4. bind（绑定 serviceId） =="
docker exec "$FATE" bash -c "cat > /tmp/publish_bind.json <<EOF
{\"service_id\": \"$SERVICE_ID\",
 \"initiator\": {\"party_id\": \"9999\", \"role\": \"guest\"},
 \"role\": {\"guest\": [\"9999\"], \"host\": [\"10000\"], \"arbiter\": [\"10000\"]},
 \"job_parameters\": {\"model_id\": \"$MODEL_ID\", \"model_version\": \"$DEPLOYED\"}}
EOF"
BIND_RC=$(in_fate "flow model bind -c /tmp/publish_bind.json" | json_field "['retcode']")
[ "$BIND_RC" = "0" ] || { echo "bind 失败 retcode=$BIND_RC" >&2; exit 1; }
echo "  bind 成功：serviceId=$SERVICE_ID"

echo "== 5. 推理自检（经 serving-proxy） =="
curl -s -m 20 -X POST http://localhost:8059/federation/v1/inference \
  -H 'Content-Type: application/json' \
  -d "{\"head\":{\"serviceId\":\"$SERVICE_ID\"},
       \"body\":{\"featureData\":{\"x0\":0.25,\"x1\":-1.04,\"x2\":0.21,\"x3\":0.07,\"x4\":-0.44,
                                  \"x5\":-0.38,\"x6\":-0.49,\"x7\":0.35,\"x8\":-0.29,\"x9\":-0.73},
                 \"sendToRemoteFeatureData\":{\"device_id\":\"133\"}}}"
echo
echo "== 完成。注意：host 侧特征取数默认 MockAdapter（score 恒 0），"
echo "   生产需实现 feature.single.adaptor 从本方在线特征源取数（见 M3 部署手册 §6）。"
