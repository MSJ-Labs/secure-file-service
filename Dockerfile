# syntax=docker/dockerfile:1

# --- Build: compile, package and split the Spring Boot jar into layers -------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Dependencies first: this layer is rebuilt only when the pom changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY lombok.config .
COPY src src
# Tests run in CI (mvn verify), not in the image build.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/secure-file-service-*.jar extract --layers --launcher --destination /extracted

# --- Runtime: JRE only, non-root ---------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app

# Least to most frequently changing, so a code change only rebuilds the last layer.
COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/ ./

USER app
EXPOSE 8080

# MaxRAMPercentage sizes the heap from the container limit. Direct memory (NIO, S3 and socket buffers)
# is outside the heap, so it is capped explicitly: container limit >= heap + direct + metaspace + stacks.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:MaxDirectMemorySize=256m -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]