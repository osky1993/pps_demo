# 隐私计算节点镜像（M2 一期）：单镜像含 platform 领域层 + psi-engine。
# JDK 锁定 17（mpc4j preview 约束 C1）；构建前先在宿主完成 mvn install（含 mpc4j 子模块）。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY ppc-common ppc-common
COPY ppc-psi ppc-psi
COPY ppc-phe ppc-phe
COPY ppc-dp ppc-dp
COPY ppc-e2e ppc-e2e
COPY ppc-bench ppc-bench
COPY platform platform
COPY engine engine
COPY third_party/mpc4j third_party/mpc4j
RUN mvn -f third_party/mpc4j/pom.xml install -q -DskipTests -pl mpc4j-s2pc-pso -am \
 && mvn install -q -DskipTests \
 && mvn -q dependency:copy-dependencies -pl engine/ppc-engine-psi -DoutputDirectory=/opt/ppc/lib \
 && cp engine/ppc-engine-psi/target/*.jar platform/ppc-platform-core/target/*.jar /opt/ppc/lib/

FROM eclipse-temurin:17-jre
RUN useradd -r -m ppc
USER ppc
COPY --from=build /opt/ppc/lib /opt/ppc/lib
WORKDIR /opt/ppc
# 入口按部署形态选择：验收演示 / 双机 netty PSI 半边（见部署手册）
ENTRYPOINT ["java", "--enable-preview", "--add-modules", "jdk.incubator.vector", \
            "-cp", "/opt/ppc/lib/*", "com.ppcdemo.engine.psi.AcceptanceDemoMain"]
CMD ["S"]
