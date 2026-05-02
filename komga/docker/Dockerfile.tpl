FROM eclipse-temurin:17-jre AS builder
ARG JAR={{distributionArtifactFile}}
WORKDIR /builder
COPY assembly/${JAR} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM ubuntu:24.04 AS build-linux
ARG TARGETARCH
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
RUN KEPUBIFY_ARCH=$([ "$TARGETARCH" = "amd64" ] && echo "64bit" || echo "$TARGETARCH") && \
    curl -sL --retry 3 \
      "https://github.com/pgaskin/kepubify/releases/latest/download/kepubify-linux-${KEPUBIFY_ARCH}" \
      -o /usr/bin/kepubify && chmod +x /usr/bin/kepubify
ENV LD_LIBRARY_PATH="/usr/lib"

# amd64
FROM build-linux AS build-amd64
ENV LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/lib/x86_64-linux-gnu"

# arm64
FROM build-linux AS build-arm64
ENV LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/lib/aarch64-linux-gnu"

FROM build-${TARGETARCH} AS runner
VOLUME /config
WORKDIR /app
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./
ENV KOMGA_CONFIGDIR="/config"
ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'
ENTRYPOINT ["java", "-Dspring.profiles.include=docker", "--enable-native-access=ALL-UNNAMED", "-jar", "application.jar", "--spring.config.additional-location=file:/config/"]
EXPOSE 25600
LABEL org.opencontainers.image.source="https://github.com/08shiro80/komga-enhanced"
