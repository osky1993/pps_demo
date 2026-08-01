package com.ppcdemo.platform.fate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeteroLrConfBuilderTest {

    @Test
    void conf模板生成含关键参数() {
        TrainingSpec spec = TrainingSpec.heteroLrDefaults("experiment",
                "breast_hetero_guest", "breast_hetero_host");
        String conf = HeteroLrConfBuilder.conf(spec);
        assertTrue(conf.contains("\"party_id\": 9999"));
        assertTrue(conf.contains("\"name\": \"breast_hetero_guest\""));
        assertTrue(conf.contains("\"max_iter\": 30"));
        assertTrue(conf.contains("evaluation_0"), "必须带评估组件（AUC 治理证据）");
        assertTrue(HeteroLrConfBuilder.dsl().contains("hetero_lr_0"));
    }

    @Test
    void 表名注入被白名单拦截() {
        assertThrows(IllegalArgumentException.class, () -> TrainingSpec.heteroLrDefaults(
                "experiment", "t\", \"evil\": \"x", "h"));
        assertThrows(IllegalArgumentException.class, () -> new TrainingSpec(
                TrainingSpec.Algorithm.HETERO_LR, 9999, 10000,
                "experiment", "g", "h", 100_000, 0.01, 0.15, 320));
    }
}
