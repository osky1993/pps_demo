-- 平台一期 schema（H2 / PostgreSQL 兼容子集）
CREATE TABLE IF NOT EXISTS task (
    task_id        VARCHAR(64)  PRIMARY KEY,
    task_type      VARCHAR(32)  NOT NULL,           -- LOCAL_STATS | PSI | JOINT_STATS
    state          VARCHAR(32)  NOT NULL,
    initiator      VARCHAR(64)  NOT NULL,
    responder      VARCHAR(64),                     -- 单方任务为空
    dataset_local  VARCHAR(256) NOT NULL,           -- 本方数据集引用
    dataset_peer   VARCHAR(256),
    protocol       VARCHAR(32),                     -- rr22 | kkrt16 | auto
    output_grain   VARCHAR(32)  NOT NULL,           -- INTERSECTION_DETAIL | CARDINALITY_ONLY | NOISED_SCALAR
    epsilon        DOUBLE PRECISION,
    clamp_upper    BIGINT,
    batch_leakage_accepted BOOLEAN DEFAULT FALSE,
    contract_fingerprint   VARCHAR(128),            -- 数据集内容指纹（S4 复核）
    fail_reason    VARCHAR(1024),
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS budget_account (
    dataset_id VARCHAR(256) PRIMARY KEY,
    total      DOUBLE PRECISION NOT NULL,
    spent      DOUBLE PRECISION NOT NULL DEFAULT 0
);

-- 出库结果唯一落点：对外结果 API 只允许读本表（出库唯一通道）
CREATE TABLE IF NOT EXISTS release_record (
    task_id        VARCHAR(64) PRIMARY KEY,
    dataset_id     VARCHAR(256) NOT NULL,
    epsilon        DOUBLE PRECISION NOT NULL,
    released_value DOUBLE PRECISION NOT NULL,
    released_at    TIMESTAMP NOT NULL
);

-- ε=0 不加噪出库的例外审批：契约各参与方均须审批（S4）
CREATE TABLE IF NOT EXISTS exception_approval (
    task_id     VARCHAR(64) NOT NULL,
    party       VARCHAR(64) NOT NULL,
    approver    VARCHAR(128) NOT NULL,
    approved_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (task_id, party)
);

-- 联邦模型注册表（M3-S2）：模型是出库物，状态流 TRAINED→RELEASE_APPROVED→EXPORTED
CREATE TABLE IF NOT EXISTS model_registry (
    task_id       VARCHAR(64)  PRIMARY KEY,
    fate_job_id   VARCHAR(64)  NOT NULL,
    fate_model_id VARCHAR(256),
    fate_model_version VARCHAR(64),
    algorithm     VARCHAR(64)  NOT NULL,
    auc           DOUBLE PRECISION,
    model_sha256  VARCHAR(64),
    state         VARCHAR(32)  NOT NULL,          -- TRAINED | RELEASE_APPROVED | EXPORTED
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);

-- ── M5 底座服务化：接入层三表（与治理内核正交，不改内核语义）──

-- 接入应用（业务系统身份）
CREATE TABLE IF NOT EXISTS pps_application (
    app_id        VARCHAR(64)  PRIMARY KEY,
    app_name      VARCHAR(128) NOT NULL,
    secret_sealed VARCHAR(512) NOT NULL,        -- HMAC 密钥经节点信封加密（DB 泄露不足以伪造，还需主密钥）
    state         VARCHAR(16)  NOT NULL,        -- ACTIVE | SUSPENDED
    created_at    TIMESTAMP    NOT NULL
);

-- 授权：应用 × 能力（业务系统只见能力，不见底层任务类型/协议/ε）
CREATE TABLE IF NOT EXISTS pps_app_grant (
    app_id        VARCHAR(64) NOT NULL,
    capability_id VARCHAR(64) NOT NULL,
    daily_limit   INT,                          -- 应用级日调用上限（与数据集级配额叠加）
    PRIMARY KEY (app_id, capability_id)
);

-- 幂等台账：业务系统的 reqId 唯一，重放返回原结果（副作用不可逆，幂等是正确性要求）
-- 同步查询与异步作业共用；异步作业的 reqId 即 jobId。
CREATE TABLE IF NOT EXISTS pps_request (
    app_id       VARCHAR(64)  NOT NULL,
    req_id       VARCHAR(128) NOT NULL,
    capability   VARCHAR(64)  NOT NULL,
    task_id      VARCHAR(64),
    state        VARCHAR(16)  NOT NULL,         -- ACCEPTED | RUNNING | SUCCEEDED | FAILED
    result_ref   VARCHAR(2048),                 -- 小结果内联；大结果为分档标记
    result_kind  VARCHAR(16),                   -- INLINE | PAGED | REF（M5-P2 结果三档）
    callback_url VARCHAR(1024),                 -- 异步作业终态回调（可空）
    fail_reason  VARCHAR(1024),
    created_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (app_id, req_id)
);

-- 异步作业的大结果（PAGED 档）：逐行存储，按 line_no 分页拉取
CREATE TABLE IF NOT EXISTS pps_job_result (
    app_id   VARCHAR(64)  NOT NULL,
    req_id   VARCHAR(128) NOT NULL,
    line_no  INT          NOT NULL,
    line     VARCHAR(4096) NOT NULL,
    PRIMARY KEY (app_id, req_id, line_no)
);

-- append-only 审计事件 + 哈希链（篡改即断链）
CREATE TABLE IF NOT EXISTS audit_event (
    seq        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id    VARCHAR(64),
    party      VARCHAR(64)  NOT NULL,
    event_type VARCHAR(64)  NOT NULL,
    payload    VARCHAR(4096),
    prev_hash  VARCHAR(64)  NOT NULL,
    hash       VARCHAR(64)  NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
