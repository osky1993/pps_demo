# ppc-demo：隐私计算 Java 技术栈 M1 PoC

验证 MPC(PSI) / PHE(Paillier) / DP 三条路线在 Java 技术栈下的可行性与性能。
设计文档见 [docs/](docs/)：

- [隐私计算Java技术栈可行性分析与总体设计.md](docs/隐私计算Java技术栈可行性分析与总体设计.md)
- [M1-PoC详细设计与开发实现计划.md](docs/M1-PoC详细设计与开发实现计划.md)

## 模块

| 模块 | 内容 |
|---|---|
| ppc-common | 数据生成（固定种子可复现）、度量采集、CSV 上报、poc.yaml 配置加载 |
| ppc-psi | PoC-1：mpc4j 两方 PSI（RR22 / KKRT16），单进程内存 RPC 版 |
| ppc-phe | PoC-2：javallier Paillier 密文聚合 |
| ppc-dp | PoC-3：Google differential-privacy 加噪出库 |
| ppc-e2e | PoC-4：端到端集成（W4 实现） |
| ppc-bench | 基准测试驱动（JMH，W3+ 实现） |

## 构建（重要：必须 JDK 17）

mpc4j 以 `release 17 + --enable-preview` 编译，javac 21 拒绝该组合，且 preview 产物要求
运行时与编译时同为 17。因此**整个工程统一用 JDK 17 构建**（ppc-psi 配了 enforcer 拦截）。

```bash
# 1. 拉取 mpc4j 子模块并构建（仅首次，install 到本地 Maven 仓库）
git submodule update --init
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -f third_party/mpc4j/pom.xml install -DskipTests -pl mpc4j-s2pc-pso -am
```

```bash
# 2. 构建本工程（含全部冒烟测试）
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn verify
```

## W1 已验证结论（2026-08-01，Apple Silicon / JDK 17）

| 项 | 结论 |
|---|---|
| mpc4j v1.1.5 | 源码构建成功（约 31s，10 模块）；默认 EnvType.STANDARD 依赖 C++ native-tool，PoC 用 `EnvType.STANDARD_JDK` 纯 Java 路径 |
| PSI S档(1万×1万) | RR22 440ms / 2.4MB；KKRT16 170ms / 1.5MB（loopback、纯 Java、含 JVM 冷启动） |
| javallier 0.6.0 | JDK 17/21 功能正常；jnagmp 无 arm64 原生库，回退纯 Java BigInteger（性能打折，密码学功能不受影响） |
| Google DP 4.0.0 | 完全正常，Count / BoundedSum 冒烟通过 |

## 配置

统一入口 [poc.yaml](poc.yaml)：数据档位（S=1万 / M=100万 / L=1000万）、PSI 协议、
Paillier 密钥位长、DP 的 ε 与 clamp 边界等。
