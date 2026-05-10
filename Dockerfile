# syntax=docker/dockerfile:1.7

# ---------- Stage 1: Build backend jar (Gradle + Node for webui) ----------
FROM eclipse-temurin:21-jdk AS backend-builder
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/build/komga-webui/node_modules \
    ./gradlew --no-daemon :komga:prepareThymeLeaf :komga:bootJar -x test

# ---------- Stage 3: Extract Spring Boot layers ----------
FROM eclipse-temurin:21-jre AS layered
WORKDIR /layered
COPY --from=backend-builder /build/komga/build/libs/komga-*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---------- Stage 4: Runtime (mirrors upstream Dockerfile.tpl) ----------
FROM ubuntu:24.04
ENV JAVA_HOME=/opt/java/openjdk
COPY --from=eclipse-temurin:23-jre $JAVA_HOME $JAVA_HOME
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get -y update && \
    apt-get -y install --no-install-recommends \
      ca-certificates locales libheif1 libwebp7 libarchive13t64 \
      curl python3 python3-pip && \
    echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen && \
    locale-gen en_US.UTF-8
RUN --mount=type=cache,target=/root/.cache/pip \
    pip3 install --break-system-packages --ignore-installed pip setuptools wheel gallery-dl
RUN curl -sL --retry 3 \
      "https://github.com/pgaskin/kepubify/releases/latest/download/kepubify-linux-64bit" \
      -o /usr/bin/kepubify && chmod +x /usr/bin/kepubify
ENV LD_LIBRARY_PATH="/usr/lib:/usr/lib/x86_64-linux-gnu"

VOLUME /config
WORKDIR /app
COPY --from=layered /layered/extracted/dependencies/ ./
COPY --from=layered /layered/extracted/spring-boot-loader/ ./
COPY --from=layered /layered/extracted/snapshot-dependencies/ ./
COPY --from=layered /layered/extracted/application/ ./
ENV KOMGA_CONFIGDIR="/config"
ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'
ENTRYPOINT ["java", "-Dspring.profiles.include=docker", "--enable-native-access=ALL-UNNAMED", "-jar", "application.jar", "--spring.config.additional-location=file:/config/"]
EXPOSE 25600
