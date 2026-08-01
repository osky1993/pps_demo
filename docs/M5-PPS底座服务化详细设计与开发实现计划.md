# M5 PPS 底座服务化：详细设计与开发实现计划

> 版本：v0.2（确立为 M5 里程碑；实施进度见 §5 与文末各期实施纪要）
> 日期：2026-08-02
> 定位：把 M1–M4 建成的平台（下称 **PPS**，Privacy-Preserving Service）从「可运行的隐私计算节点」
> 演进为「**业务系统可依赖的底座服务**」
> 前置：M4 已收官（MPC/HE/DP 落地 + FL 集成，73 项测试）
> 交付节奏：P1（最小可用底座）→ P2（异步作业）→ P3（生产化），每期独立门禁 + 提交

---

## 1. 可行性评估（先说不足）

### 1.1 内核已具备底座资质

| 能力 | 状态 | 依据 |
|---|---|---|
| 协议引擎 | PSI（RR22/KKRT16/分批）、PIR（PAI_CKS/SIMPLE_NAIVE）、Paillier、DP | 活体基准与真值校验 |
| 治理内核 | 契约不可变、状态机、审计哈希链、预算/配额网关、出库唯一通道 | 自动化测试背书 |
| 跨方通信 | 控制面 mTLS、数据通道 mTLS、协议面 TLS 隧道 | 门禁测试（含负向） |
| 引擎隔离 | 独立进程、按协议族分派、故障不拖垮平台 | ADR-1 |

**这部分不需要重做**——它恰好是业务系统最难自建、也最需要外部保障的部分。

### 1.2 六个接入层缺口（代码核查结论）

| # | 缺口 | 证据 | 后果 |
|---|---|---|---|
| G1 | 管理面仅绑 loopback | `ManagementServer` 用 `getLoopbackAddress()` | 跨机不可达 |
| G2 | 无认证鉴权 | 全模块无 `Authorization`/appKey 痕迹 | 无法识别调用方、无法授权 |
| G3 | 同步阻塞执行 | `runJointStats` 注释「同步阻塞至完成」 | 分钟级任务挂死业务线程 |
| G4 | 无幂等语义 | 无 `clientRequestId` 或唯一约束 | **重发即重复扣预算**（不可逆副作用） |
| G5 | 无应用/租户维度 | `task` 表只有 initiator/responder（参与**方**，非调用**应用**） | 审计答不出「哪个系统发起的」 |
| G6 | 结果出口是标量 | `releasedValue() → Optional<Double>` | 明细类结果（PSI 交集、PIR 批量）无统一出口 |

**判断：G1–G6 全部属于接入层，不触及治理内核与协议实现。**
因此结论是「**可以，且改造是加法而非重构**」。

### 1.3 不适合承担的职责（边界）

PPS 是**隐私计算能力的底座**，不是：

- **业务数据中台**：不长期存储业务数据，只在任务生命周期内接触数据；
- **通用 API 网关**：不承担业务系统之间的一般集成；
- **实时联机交易组件**：除 PIR 与联邦推理外，多数能力是秒级到小时级作业，
  不可放进交易主链路的同步路径。

---

## 2. 调用关系分析

### 2.1 三类调用模式（语义不同，不可一套 API 打天下）

这是整个设计的核心判断：**隐私计算能力的时延跨越 6 个数量级**（4ms 的 PIR 到小时级的联邦训练），
用统一的同步接口或统一的异步接口都是错的。

| 模式 | 能力 | 时延 | 接口语义 | 业务位置 |
|---|---|---|---|---|
| **A. 同步查询** | PIR 匿踪查询、已建库的小规模查询 | 毫秒级（实测 4–6 ms） | 请求/响应，同步返回结果 | **可入交易主链路** |
| **B. 异步作业** | PSI 求交、联合统计、联邦训练 | 秒级~小时级 | 提交→jobId→回调/轮询→取结果 | 旁路批处理、离线分析 |
| **C. 在线推理** | 联邦模型打分 | 十毫秒级（实测 47 ms） | 请求/响应，同步 | 可入交易主链路（需降级预案） |

### 2.2 调用时序

**模式 A（同步查询）**——业务系统视角只是一次 RPC：

```
业务系统 ──① query(capability=credit-check, key=user123, reqId) ──▶ PPS Gateway
                                                                      │ 鉴权/限流/配额预扣
                                                                      ▼
                                                              PIR 引擎 ⇄ 数据方节点
         ◀────────────── ② {score-720, quotaRemaining: 9993} ─────────┘
```

**模式 B（异步作业）**——两段式，回调优先、轮询兜底：

```
业务系统 ──① submit(capability=joint-stats, params, reqId, callbackUrl) ─▶ PPS
         ◀── ② {jobId, state: ACCEPTED}                                    （立即返回）
                                                                    协商→执行→出库网关
         ◀── ③ callback{jobId, state: SUCCEEDED, resultRef}   ──────────── （终态推送，带签名）
         ──④ getResult(jobId) ─▶  ◀── 结果（小则内联、大则分片/对象存储引用）
```

**模式 C（在线推理）**：形同 A，但走 FATE-Serving 通道，需业务侧配置降级策略
（模型不可用时的兜底规则）。

### 2.3 调用方与被调方的责任边界

| 责任 | 业务系统 | PPS |
|---|---|---|
| 提供查询参数/数据集引用 | ✅ | |
| 选择 PSI 协议、设定 ε、配 clamp 边界 | ❌ **不应关心** | ✅ 由能力目录预置（见 §4） |
| 幂等键生成（`reqId`） | ✅ | ✅ 据此去重 |
| 重试 | ✅ 带同一 reqId 重试 | ✅ 保证同 reqId 不重复执行 |
| 结果解释与业务决策 | ✅ | ❌ 不介入业务语义 |
| 合规留痕 | 保留 reqId ↔ 业务单据映射 | ✅ 全链路审计（不含 PIR 查询内容） |

---

## 3. PPS 侧实现设计

### 3.1 分层（新增只在最外层）

```
┌──────────────────── 业务系统（Java / 其他语言）─────────────────────┐
│  PPS Java SDK（签名/重试/轮询/回调校验）  或  直接 HTTPS               │
└───────────────────────────────┬────────────────────────────────────┘
                                │ HTTPS + mTLS 或 AppKey 签名
┌───────────────────────────────▼────────────────── 新增：接入层 ─────┐
│  ppc-platform-gateway                                               │
│   ├─ 认证与授权（应用身份 → 可用能力 → 可用数据集 → 配额）  ← G2/G5  │
│   ├─ 幂等（reqId 唯一约束，重放返回原结果）                 ← G4     │
│   ├─ 限流与并发闸（防单应用打爆引擎）                                │
│   ├─ 作业受理与状态机对接（同步 fast-path / 异步 submit）   ← G3     │
│   ├─ 结果交付（内联 / 分片 / 引用）                         ← G6     │
│   └─ 回调发送（HMAC 签名、指数退避重试）                             │
└───────────────────────────────┬────────────────────────────────────┘
                                │ 进程内调用（不新增网络跳）
┌───────────────────────────────▼──────────── 既有：治理内核（不改）──┐
│  TaskService / NegotiationService / *Orchestrator                    │
│  ReleaseGateway（出库唯一通道）· AuditCenter · BudgetCenter/Quota     │
└───────────────────────────────┬────────────────────────────────────┘
                                ▼
                       引擎进程（PSI/PIR）· FATE · 对方节点
```

**关键约束：接入层不得绕过治理内核。** 结果读取一律经 `ReleaseGateway`/结果表，
不新增任何直连引擎或直读中间产物的路径——否则服务化即成为治理的后门。

### 3.2 应用身份与授权模型（G2/G5）

新增三张表，与既有 schema 正交：

```sql
-- 接入应用（业务系统）
CREATE TABLE pps_application (
    app_id        VARCHAR(64) PRIMARY KEY,
    app_name      VARCHAR(128) NOT NULL,
    secret_hash   VARCHAR(128) NOT NULL,       -- AppSecret 只存哈希；或改用 mTLS 客户端证书指纹
    owner_contact VARCHAR(128),
    state         VARCHAR(16) NOT NULL,        -- ACTIVE | SUSPENDED
    created_at    TIMESTAMP NOT NULL
);

-- 授权：应用 × 能力（而非应用 × 原始任务类型——见 §4 能力目录）
CREATE TABLE pps_app_grant (
    app_id        VARCHAR(64)  NOT NULL,
    capability_id VARCHAR(64)  NOT NULL,
    daily_limit   INT,                          -- 应用级调用上限（与数据集级配额叠加生效）
    PRIMARY KEY (app_id, capability_id)
);

-- 幂等台账
CREATE TABLE pps_request (
    app_id     VARCHAR(64)  NOT NULL,
    req_id     VARCHAR(128) NOT NULL,           -- 业务系统生成，全局唯一
    task_id    VARCHAR(64),
    state      VARCHAR(16)  NOT NULL,           -- ACCEPTED | SUCCEEDED | FAILED
    result_ref VARCHAR(512),
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (app_id, req_id)
);
```

**任务归属**：`task` 表增列 `app_id`（可空，兼容既有任务），审计事件带 `appId`——
自此审计能回答「**哪个业务系统、凭哪个请求、查了什么数据集、消耗多少配额**」。

**双层配额**：应用级（`daily_limit`，防单应用滥用）× 数据集级（既有 ε/查询配额，防整体泄露）——
两层独立生效，任一耗尽即拒。

### 3.3 幂等设计（G4，底座的硬要求）

隐私计算的副作用**不可逆**（预算扣了就是扣了、对方节点已看到协商请求），
因此幂等不是优化而是正确性要求：

```
提交请求（app_id, req_id）
  ├─ pps_request 插入（主键冲突即命中幂等）
  │    ├─ 冲突且原请求 SUCCEEDED → 直接返回原结果，不重复执行
  │    ├─ 冲突且原请求 ACCEPTED  → 返回原 jobId（同一作业，勿重复提交）
  │    └─ 冲突且原请求 FAILED    → 按能力策略：可重试类新建作业，不可重试类返回原错误
  └─ 无冲突 → 受理，与预算预扣同事务
```

预扣与幂等台账写入**同一事务**——这与 `ReleaseGateway` 既有的「预算扣减+出库+审计同事务」
是同一原则的延伸。

### 3.4 异步作业模型（G3）

现有编排是同步阻塞的，改造为：

- **受理即返回**：Gateway 写 `pps_request` + 创建任务（CREATED/ACCEPTED）后立即返回 `jobId`；
- **执行走作业队列**：单节点内用有界线程池 + DB 状态驱动（任务状态机已具备，无需引入 MQ）；
  并发闸按能力配置（PSI 大任务并发 1–2，PIR 可高并发）；
- **终态通知**：回调优先（HMAC-SHA256 签名 + 指数退避重试 5 次），轮询兜底（`GET /jobs/{id}`）；
- **超时与取消**：能力目录声明最长执行时长，超时自动 FAILED 并可重跑（既有幂等重跑语义）。

**为什么不引 MQ**：单节点内任务量级（每日百到千级）远未到需要 MQ 的规模，
DB 状态机 + 线程池足够，且避免给试点机构增加中间件运维负担。规模上来再演进，接口不变。

### 3.5 结果交付（G6）

按结果规模分三档，**统一经出库网关**：

| 档 | 判据 | 交付方式 |
|---|---|---|
| 标量/小结果 | ≤ 64 KB（加噪统计值、PIR 单批、交集基数） | 内联在 `getResult` 响应体 |
| 中等明细 | ≤ 100 MB（PSI 交集明细） | 分页拉取 `GET /jobs/{id}/result?cursor=` |
| 大结果 | 更大 | 写对象存储，返回**限时签名 URL**（默认 15 分钟，一次性） |

三档共同约束：**结果读取本身是治理事件**——读取即记审计，且受契约的输出粒度约束
（`CARDINALITY_ONLY` 的任务永远拿不到明细，这是既有的引擎侧销毁语义在接入层的延续）。

### 3.6 Java SDK（业务侧体验）

业务系统多为 Java，提供轻量 SDK（零重依赖，仅 JDK HttpClient）：

```java
PpsClient pps = PpsClient.builder()
        .endpoint("https://pps-node-a.internal:8443")
        .appId("risk-control-system")
        .credentials(AppCredentials.fromEnv())      // 或 mTLS 客户端证书
        .build();

// 模式 A：同步匿踪查询（毫秒级，可入主链路）
CreditResult r = pps.query("credit-check")
        .key(customerId)
        .requestId(bizOrderId)                       // 幂等键 = 业务单据号
        .execute();

// 模式 B：异步作业（提交即返回）
JobHandle job = pps.submit("joint-consumption-stats")
        .dataset("ds://A/customers")
        .partner("B")
        .requestId(batchId)
        .callback("https://risk.internal/pps-callback")
        .submit();
// 回调到达后
JobResult result = pps.getResult(job.jobId());
```

SDK 职责：签名、超时、**带同一 reqId 的安全重试**、回调签名校验、错误分类
（可重试 vs 不可重试）。SDK **不做**业务语义解释。

---

## 4. 「优雅」的核心：能力目录（Capability Catalog）

这是本设计最重要的一处抽象。

### 4.1 问题

若 API 直接暴露 `taskType=PSI&protocol=rr22&epsilon=1.0&clampUpper=100000`，
则**业务开发者被迫成为隐私计算专家**——他们会填错 ε、选错协议、误设输出粒度，
而这些参数直接决定隐私保护强度。这既不安全也不可持续。

### 4.2 设计

引入**能力（Capability）**作为业务系统唯一可见的调用单元：

```yaml
# 能力目录条目（由 PPS 管理员 + 双方合规人员共同定义，非业务系统自定义）
capability:
  id: credit-check
  displayName: "共同客户信用核验"
  mode: SYNC_QUERY                    # 决定业务系统看到的接口形态
  # ↓ 技术参数全部预置，业务系统不可见、不可覆盖
  taskType: PIR_QUERY
  protocol: pai_cks
  partner: B-bureau
  dataset: csv://B/credit-scores
  outputGranularity: INTERSECTION_DETAIL
  valueBits: 128
  quotaPolicy: { datasetDaily: 10000, perAppDaily: 2000 }
  # ↓ 业务系统可传的，只有这些
  inputSchema:
    key: { type: string, required: true, description: "客户标识" }
```

业务系统的调用因此退化为：**能力 ID + 业务参数 + 幂等键**。

### 4.3 收益

| 维度 | 效果 |
|---|---|
| 安全 | ε/协议/输出粒度由懂的人一次性设定，业务无法调错 |
| 合规 | 能力即「已审批的数据使用场景」，能力目录 = 数据使用清单，天然对齐监管问询 |
| 演进 | 协议升级（如 PSI 换 RR22 分批、PIR 换协议）对业务**完全透明** |
| 可读 | 审计日志记的是"信用核验"而非"PSI 任务"，业务与合规都看得懂 |

**这是把 M1–M4 积累的技术判断（协议选型、ε 语义、盲化时机、输出粒度）固化成产品资产的方式**——
不让每个业务方重新踩一遍坑。

---

## 5. 实施路线（三期，可独立交付）

| 期 | 内容 | 验收 |
|---|---|---|
| **P1 最小可用底座** | Gateway 模块（认证/幂等/限流）、应用与授权表、能力目录（配置文件驱动）、同步查询模式（PIR）、Java SDK v0 | 业务系统跨机调用一次匿踪查询，鉴权/幂等/配额三重生效，审计含 appId |
| **P2 异步作业** | 作业队列与并发闸、回调（签名+重试）、轮询接口、结果三档交付、失败重试语义 | PSI/联合统计经 SDK 提交并回调，重发同 reqId 不重复扣预算 |
| **P3 生产化** | 能力目录管理台（增删改需双方审批）、多应用隔离压测、可观测（任务级 trace/指标）、SDK v1 与接入手册 | 试点业务系统接入，全链路可观测 |

**P1 即可让业务系统真实使用**（匿踪查询是最容易独立见效的场景：单方发起、毫秒级、无需协商窗口）。

---

## 6. 风险与对策

| # | 风险 | 对策 |
|---|---|---|
| R1 | 服务化成为治理后门（绕过网关直取结果） | 架构约束：接入层只读结果表、只经编排层发起；**代码评审红线 + 集成测试断言无旁路** |
| R2 | 业务系统滥用（高频调用等价拖库） | 双层配额（应用级 × 数据集级）+ 限流；PIR 配额语义已就绪 |
| R3 | 同步能力被放进交易主链路后 PPS 故障拖垮业务 | 接入手册强制要求业务侧降级预案；SDK 默认超时 + 熔断 |
| R4 | 能力目录膨胀失控 | 能力新增须双方合规审批并留档（复用既有例外审批机制） |
| R5 | 回调不可达（业务系统在内网） | 轮询兜底始终可用；回调仅为优化 |

---

## P1 实施纪要（2026-08-02）

新增 `ppc-platform-gateway` 模块（纯 JDK 17，依赖 platform-core），落地 G1–G6：

| 组件 | 修的缺口 | 说明 |
|---|---|---|
| `PpsGatewayServer` | G1 | 绑定可配置 host（默认 0.0.0.0），跨机可达 |
| `HmacSigner` + `AppAuthenticator` + `AppRegistry` | G2 | HMAC-SHA256 请求签名 + 重放窗口 + 常量时间比较；**密钥经 M2.5 信封加密入库**（DB 泄露不足以伪造，还需主密钥） |
| `IdempotencyStore` | G4 | INSERT-first 认领，与配额预扣同事务；重放返回原结果、不重复执行/扣费 |
| `AppDailyQuota` | G5 | 应用级日配额（键含日期天然日切），复用 BudgetCenter 行锁 |
| `pps_*` 表 + appId 审计 | G5 | 审计回答「哪个业务系统、凭哪个请求、调了哪个能力」——绝不含查询键 |
| `CapabilityCatalog` + `QueryGateway` | G6/§4 | 能力目录驱动；同步查询 fast-path；结果内联交付 |
| `PpsClient` | §3.6 | Java SDK v0：签名/超时/错误分类（4xx 不重试、5xx 可重试） |

**一处设计修正（比原设计更正确）**：原设计把「双层配额」都放 gateway，实现时发现——
数据集级配额若由 gateway 拥有，则**绕过 gateway 即绕过治理**，违背红线 R1。
故修正为：**应用级配额留 gateway（接入 concern），数据集级配额留 orchestrator（治理 concern，任何入口都强制）**。
双层依旧生效，只是各落在正确的层。这正是"服务化不得成为后门"在代码层的体现。

**门禁**：gateway 接入层 7 项 + HTTP 往返 3 项 + **活体门禁**（业务系统 → Gateway → 真实 PIR
→ 返回 user-7 真值 score-607，appId 入审计、查询键不入审计、征信方 `queryContentVisible:false`）。
全量 84 项测试全绿。

---

## 7. 结论

PPS 具备成为隐私计算底座的**内核条件**，缺口集中在接入层且均为加法改造（G1–G6）。
设计的核心主张有三条：

1. **按时延分三类调用模式**，而非用一套 API 强行统一；
2. **能力目录**把隐私参数从业务系统手里收回给懂的人——这是"优雅"的真正含义，
   也是把四个里程碑的技术判断沉淀为可复用资产的方式；
3. **服务化不得成为治理的后门**——所有出口仍走既有网关，这是底座能被信任的前提。
