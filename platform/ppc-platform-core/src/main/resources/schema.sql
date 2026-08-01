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
