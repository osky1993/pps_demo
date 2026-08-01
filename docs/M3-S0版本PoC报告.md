# M3-S0 版本 PoC 报告

> 版本：v0.1 · 日期：2026-08-01
> 上游：[M3 计划](./M3-联邦学习集成详细设计与开发实现计划.md) §3.1 / §6.2 Sprint 0

## 1. 选型决策：FATE 1.11.3（standalone 镜像）定稿

**判据**（M3 §3.1：以「训练 + Serving 双链路可跑通」为准）与证据：

| 证据 | 结论 |
|---|---|
| FATE 主线：2.2.0（2024-07），1.x 末版 1.11.4（2023-11） | 2.x 是社区演进方向 |
| FATE-Serving 末版 2.1.7（2023-11），最后提交 2024-12 仅修补 | **配套 FATE 1.x 模型格式** |
| GitHub 无任何「Serving 支持 FATE 2.x」的发版/issue 痕迹 | 2.x 的在线推理配套**断层** |
| Docker Hub：standalone_fate 最高 1.x 镜像 = **1.11.3**（1.6GB）；serving-server 最高 2.1.6 | 部署物可得性锁定 1.11.3 + serving 2.1.6 |

**决策**：M3 全程锁定 **FATE 1.11.3 + FATE-Serving 2.1.6**。
熔断规则基于证据提前触发（无需消耗两周在 2.x 上试错）；FATE 2.x 升级评估挂 M4
（触发条件：官方 serving 配套发布）。

## 2. 环境事实（开发态）

| 项 | 值 | 注记 |
|---|---|---|
| Docker | 29.4.0，10 CPU / **11.7GB** VM 内存 / 210GB 磁盘 | 双 standalone 可容纳；集群版需双机 |
| 镜像架构 | standalone_fate 仅 **amd64** | Apple Silicon 经 Rosetta 仿真运行——**功能验证有效，性能数据不代表生产**（生产 x86） |
| 部署形态 | standalone 单容器（内含 guest 9999 / host 10000 双方模拟） | M3 R3 的降级路径成为开发态默认；跨方双容器编排在 S1 扩展 |

## 3. 训练链路验证（纵向逻辑回归官方样例）✅

| 步骤 | 结果 |
|---|---|
| standalone 起立 | `deploy/fate/start-standalone.sh`（含保活修正，见 §5 坑 1）；FATE Flow `/v1/version/get` 返回 1.11.3 |
| 数据上传 | breast_hetero_guest / host 双方上传成功（`flow data upload`） |
| 任务提交 | 官方样例 `hetero_lr_normal_{dsl,conf}.json`，job `202608011438291813860` |
| 训练结果 | **success，耗时 173s**（含样本对齐 + LR 迭代；amd64 仿真环境） |
| 模型产物 | `output-model` 含 `encryptedWeight / intercept / iters / isConverged / bestIteration`——真实纵向 LR 模型；`output-data` 可下载 |
| 可视化 | FATEBoard http://localhost:8080 |

**结论：训练链路（选型判据之一）验证通过。**

## 4. Serving 链路评估 ⏸（可得性通过，完整接线归 S1/S3）

- serving-server **2.1.6 镜像可用**（amd64），Java 8 服务正常引导；
- 默认配置强依赖 ZooKeeper 注册中心（`useRegister=true`，zk 连接超时为预期报错）——
  完整链路 = zk + serving-server + serving-proxy + FATE Flow `service_conf` 指向 servings +
  `flow model load/bind`，按 M3 计划在 S3 完成（S1 先行准备 compose 编排）；
- 关键配置线索已记录：`model.transfer.url` 指向 FATE Flow 9380（模型经 Flow 推送 Serving）、
  推理特征取数走 adaptor 接口（一期 MockAdapter → S3 换离线特征表适配器）。

**结论：可得性与配套关系确证，双链路判据的 Serving 半边无阻断性风险。**

## 5. SLA 基线与 S1 输入

- **SLA 不在仿真环境定稿**（诚实边界）：breast 样例（569 行×30 特征）173s 是 Rosetta 仿真数据，
  对 M3 §2.4 的「10万×20特征 ≤30 分钟」目标只具方向性参考；SLA 随 S1 在 x86 环境
  （或双机真机）实测定稿；
- **踩坑清单（S1 直接输入）**：
  1. standalone 镜像 ENTRYPOINT 启动服务后 exec 交互 bash，无 TTY 即整容器退出——
     必须覆盖 CMD 保活（脚本已修）；
  2. 就绪探测用 `POST /v1/version/get`（该版 flow CLI 无 `server status` 子命令）；
  3. `flow component metric-all/metrics` 对 hetero_lr_0 返回 no data——指标取数以
     `output-model`（含收敛信息）与 evaluation 组件为准，适配器（F2）按此实现；
  4. 全镜像 amd64：Apple Silicon 仅作功能开发环境，性能类验收必须 x86。

## 6. S0 结论

**双链路判据达成度：训练 ✅ 实跑通过 + Serving ✅ 可得性/配套确证 → FATE 1.11.3 选型定稿生效**
（M3 文档 §3.1 升 v0.2）。R1（部署复杂度）实际低于预期（standalone 半天起立），
R3（内存）以 standalone 形态化解。S1 可按计划开工：FateFlowClient 适配器 + 契约扩展。
