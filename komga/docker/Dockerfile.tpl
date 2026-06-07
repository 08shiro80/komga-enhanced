FROM eclipse-temurin:17-jre AS builder
ARG JAR={{distributionArtifactFile}}
WORKDIR /builder
COPY assembly/${JAR} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM debian:bookworm-slim AS build-linux
ARG TARGETARCH
ENV JAVA_HOME=/opt/java/openjdk
COPY --link --from=eclipse-temurin:25-jre-jammy $JAVA_HOME $JAVA_HOME
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN --mount=type=cache,target=/var/cache/apt,id=apt-cache-${TARGETARCH},sharing=locked \
    --mount=type=cache,target=/var/lib/apt,id=apt-lib-${TARGETARCH},sharing=locked \
    apt-get -y update && \
    apt-get -y install --no-install-recommends \
      ca-certificates libheif1 libwebp7 libjxl0.7 libarchive13 \
      curl python3 python3-pip zip locales && \
    sed -i '/^# *\(en_US\|de_DE\|ja_JP\|ko_KR\|zh_CN\|zh_TW\|fr_FR\|es_ES\|it_IT\|pt_BR\|ru_RU\)\.UTF-8 UTF-8/s/^# *//' /etc/locale.gen && \
    locale-gen && \
    rm -rf /var/lib/apt/lists/*
RUN KEPUBIFY_ARCH=$([ "$TARGETARCH" = "amd64" ] && echo "64bit" || echo "$TARGETARCH") && \
    curl -sL --retry 3 \
      "https://github.com/pgaskin/kepubify/releases/latest/download/kepubify-linux-${KEPUBIFY_ARCH}" \
      -o /usr/bin/kepubify && chmod +x /usr/bin/kepubify
ARG GALLERY_DL_REV=local
RUN --mount=type=cache,target=/root/.cache/pip,id=pip-${TARGETARCH} \
    echo "gallery-dl-komga rev: ${GALLERY_DL_REV}" && \
    GALLERY_DL_SHA=$(curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 --max-time 60 \
      https://api.github.com/repos/08shiro80/gallery-dl-komga/commits/master \
      | python3 -c "import sys,json;print(json.load(sys.stdin)['sha'])") && \
    mkdir -p /opt && echo "$GALLERY_DL_SHA" > /opt/gallery-dl-fork-sha && \
    curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 --max-time 180 \
      -o /tmp/gallery-dl-fork.tar.gz \
      https://codeload.github.com/08shiro80/gallery-dl-komga/tar.gz/${GALLERY_DL_SHA} && \
    pip3 install --break-system-packages --no-cache-dir --force-reinstall \
      /tmp/gallery-dl-fork.tar.gz && \
    rm /tmp/gallery-dl-fork.tar.gz
ENV LD_LIBRARY_PATH="/usr/lib"

# amd64
FROM build-linux AS build-amd64
ENV LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/lib/x86_64-linux-gnu"
RUN cd /usr/lib/x86_64-linux-gnu && \
    ln -sf libwebp.so.7 libwebp.so && \
    ln -sf libheif.so.1 libheif.so && \
    ln -sf libjxl.so.0.7 libjxl.so && \
    ln -sf libarchive.so.13 libarchive.so

# arm64
FROM build-linux AS build-arm64
ENV LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/lib/aarch64-linux-gnu"
RUN cd /usr/lib/aarch64-linux-gnu && \
    ln -sf libwebp.so.7 libwebp.so && \
    ln -sf libheif.so.1 libheif.so && \
    ln -sf libjxl.so.0.7 libjxl.so && \
    ln -sf libarchive.so.13 libarchive.so

FROM build-${TARGETARCH} AS runner
VOLUME /config
WORKDIR /app
COPY --link --from=builder /builder/extracted/dependencies/ ./
COPY --link --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --link --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --link --from=builder /builder/extracted/application/ ./
ENV KOMGA_CONFIGDIR="/config"
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8
ENTRYPOINT ["java", "-Dspring.profiles.include=docker", "--enable-native-access=ALL-UNNAMED", "-jar", "application.jar", "--spring.config.additional-location=file:/config/"]
EXPOSE 25600
LABEL org.opencontainers.image.source="https://github.com/08shiro80/komga-enhanced"
