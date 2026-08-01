package com.ppcdemo.platform.fate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 纵向 LR 的 DSL/conf 生成（M3 §4.2）：DSL 用官方样例原文（资源内嵌，含 evaluation 组件）；
 * conf 由受控模板生成——参数全部来自 TrainingSpec 白名单字段，杜绝自由 JSON 注入。
 */
public final class HeteroLrConfBuilder {

    private HeteroLrConfBuilder() {
    }

    public static String dsl() {
        try (InputStream in = HeteroLrConfBuilder.class
                .getResourceAsStream("/fate-templates/hetero_lr_dsl.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 DSL 模板失败", e);
        }
    }

    public static String conf(TrainingSpec spec) {
        if (spec.algorithm() != TrainingSpec.Algorithm.HETERO_LR) {
            throw new IllegalArgumentException("本模板仅支持 HETERO_LR：" + spec.algorithm());
        }
        return """
                {
                  "dsl_version": 2,
                  "initiator": {"role": "guest", "party_id": %1$d},
                  "role": {"arbiter": [%2$d], "host": [%2$d], "guest": [%1$d]},
                  "component_parameters": {
                    "role": {
                      "host": {"0": {
                        "reader_0": {"table": {"name": "%5$s", "namespace": "%3$s"}},
                        "data_transform_0": {"with_label": false}}},
                      "guest": {"0": {
                        "reader_0": {"table": {"name": "%4$s", "namespace": "%3$s"}},
                        "data_transform_0": {"with_label": true}}}
                    },
                    "common": {
                      "data_transform_0": {"output_format": "dense"},
                      "hetero_lr_0": {
                        "penalty": "L2", "tol": 0.0001,
                        "alpha": %6$s, "optimizer": "rmsprop",
                        "batch_size": %7$d, "learning_rate": %8$s,
                        "init_param": {"init_method": "zeros"},
                        "max_iter": %9$d, "early_stop": "diff"
                      },
                      "evaluation_0": {"eval_type": "binary"}
                    }
                  }
                }
                """.formatted(spec.guestPartyId(), spec.hostPartyId(), spec.namespace(),
                spec.guestTable(), spec.hostTable(), spec.alpha(), spec.batchSize(),
                spec.learningRate(), spec.maxIter());
    }
}
