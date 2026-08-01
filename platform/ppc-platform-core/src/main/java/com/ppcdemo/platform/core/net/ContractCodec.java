package com.ppcdemo.platform.core.net;

import com.ppcdemo.platform.core.domain.TaskContract;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * 契约线上编解码（控制面 wire format v0：java.util.Properties 文本）。
 * 选型理由：零依赖、转义健壮、字段平铺与契约结构天然对应；升级 JSON/protobuf 时只改本类。
 */
public final class ContractCodec {

    private ContractCodec() {
    }

    public static String encode(TaskContract c) {
        Properties p = new Properties();
        p.setProperty("taskId", c.taskId());
        p.setProperty("taskType", c.taskType().name());
        p.setProperty("initiator", c.initiator());
        set(p, "responder", c.responder());
        p.setProperty("datasetLocal", c.datasetLocal());
        set(p, "datasetPeer", c.datasetPeer());
        set(p, "protocol", c.protocol());
        p.setProperty("outputGranularity", c.outputGranularity().name());
        set(p, "epsilon", c.epsilon() == null ? null : String.valueOf(c.epsilon()));
        set(p, "clampUpper", c.clampUpper() == null ? null : String.valueOf(c.clampUpper()));
        p.setProperty("batchLeakageAccepted", String.valueOf(c.batchLeakageAccepted()));
        set(p, "contractFingerprint", c.contractFingerprint());
        try (StringWriter out = new StringWriter()) {
            p.store(out, "ppc contract v0");
            return out.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 解码对方节点视角的契约：datasetLocal/datasetPeer 互换（各节点的 local 指本方数据集）。
     */
    public static TaskContract decodeAsResponder(String wire) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(wire));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new TaskContract(
                p.getProperty("taskId"),
                TaskContract.TaskType.valueOf(p.getProperty("taskType")),
                p.getProperty("initiator"),
                p.getProperty("responder"),
                // 视角互换：发起方的 peer 数据集是响应方的 local
                p.getProperty("datasetPeer"),
                p.getProperty("datasetLocal"),
                p.getProperty("protocol"),
                TaskContract.OutputGranularity.valueOf(p.getProperty("outputGranularity")),
                opt(p, "epsilon") == null ? null : Double.valueOf(opt(p, "epsilon")),
                opt(p, "clampUpper") == null ? null : Long.valueOf(opt(p, "clampUpper")),
                Boolean.parseBoolean(p.getProperty("batchLeakageAccepted", "false")),
                opt(p, "contractFingerprint"));
    }

    private static void set(Properties p, String key, String value) {
        if (value != null) {
            p.setProperty(key, value);
        }
    }

    private static String opt(Properties p, String key) {
        return p.getProperty(key);
    }
}
