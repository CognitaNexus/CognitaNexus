# 采集服务镜像。
#
# 与 core-service 的 Dockerfile 只差三处：模块名、暴露端口、健康检查端口。
# 刻意保留三份独立文件而不抽公共模板：这三个镜像将来很可能分道扬镳（采集需要长时间运行的
# worker，查询服务是无状态只读），现在合成一份模板，等到分岔时反而要拆。

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
    mvn -B -pl services/ingestion-service -am dependency:go-offline -DskipTests

COPY libraries/ libraries/
COPY services/ingestion-service/ services/ingestion-service/

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -pl services/ingestion-service -am package -DskipTests -Dspotless.check.skip=true

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

COPY --from=build /workspace/services/ingestion-service/target/ingestion-service-*.jar /app/app.jar

RUN chown -R app:app /app

USER app

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"

EXPOSE 8082

# 健康检查打的是 actuator health，不是首页：首页能打开不代表数据源已就绪。
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8082/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
