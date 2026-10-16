# 核心服务镜像。
#
# 构建上下文是**仓库根**而不是服务目录：三个服务都依赖 libraries/shared-kernel 与父 POM，
# 把上下文限定在 services/core-service 会导致依赖模块找不到。compose 里已按此设置
# （context: .  dockerfile: services/core-service/Dockerfile）。
#
# 分两阶段：构建阶段带完整 JDK 与 Maven，运行阶段只留 JRE。运行镜像里没有编译工具链，
# 也没有源码，缩小了攻击面。

# ------------------------------ 构建阶段 ------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# 先只复制 POM 再复制源码：依赖不变时这一层能命中缓存，改代码不会重新下载依赖。
COPY pom.xml ./
COPY libraries/shared-kernel/pom.xml libraries/shared-kernel/pom.xml
COPY services/core-service/pom.xml services/core-service/pom.xml
COPY services/ingestion-service/pom.xml services/ingestion-service/pom.xml
COPY services/query-service/pom.xml services/query-service/pom.xml
COPY ops/compose/maven-settings.xml /root/.m2/settings.xml

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -pl services/core-service -am dependency:go-offline -DskipTests

COPY libraries/ libraries/
COPY services/core-service/ services/core-service/

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -pl services/core-service -am package -DskipTests -Dspotless.check.skip=true

# ------------------------------ 运行阶段 ------------------------------
FROM eclipse-temurin:21-jre

# 以非 root 运行。容器逃逸的利用链里，「进程是 root」几乎总是第一步。
# curl 只用于下面的健康检查：基础镜像不含它，而用一个自制的 TCP 探测会检查得过浅
# （端口通了不等于数据源已就绪）。
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app --home /app --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /workspace/services/core-service/target/core-service-*.jar /app/app.jar

# 认领材料等文件落在挂载卷上，容器重建不丢。
RUN mkdir -p /app/storage/claim-evidence && chown -R app:app /app
VOLUME ["/app/storage"]

USER app

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"

EXPOSE 8081

# 健康检查打的是 actuator health，不是首页：首页能打开不代表数据源已就绪。
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8081/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
