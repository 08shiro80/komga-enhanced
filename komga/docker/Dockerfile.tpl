FROM eclipse-temurin:17-jre AS builder
ARG JAR={{distributionArtifactFile}}
WORKDIR /builder
COPY assembly/${JAR} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM golang:1.24 AS kepubify-builder
RUN go install github.com/pgaskin/kepubify/v4/cmd/kepubify@latest

# amd64/arm64 shared base
FROM ubuntu:24.04 AS build-linux
ARG TARGETARCH
ENV JAVA_HOME=/opt/java/openjdk
COPY --from=eclipse-temurin:23-jre $JAVA_HOME $JAVA_HOME
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get -y update && \
    apt-get -y upgrade --no-install-recommends && \
    apt-get -y install --no-install-recommends \
      ca-certificates locales libjxl-dev libheif-dev libwebp-dev libarchive-dev \
      curl python3 python3-pip && \
    echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen && \
    locale-gen en_US.UTF-8
RUN --mount=type=cache,target=/root/.cache/pip \
    pip3 install --break-system-packages --ignore-installed pip setuptools wheel gallery-dl
COPY --from=kepubify-builder /go/bin/kepubify /usr/bin/kepubify
ENV LD_LIBRARY_PATH="/usr/lib"

# amd64
FROM build-linux AS build-amd64
ENV LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/lib/x86_64-linux-gnu"

# arm64
FROM build-linux AS build-arm64
ENV LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/lib/aarch64-linux-gnu"

# arm32: uses temurin-17, as arm32 support was dropped in JDK 21
FROM eclipse-temurin:17-jre AS build-arm
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get -y update && \
    apt-get -y upgrade --no-install-recommends && \
    apt-get -y install --no-install-recommends curl python3 python3-pip
RUN --mount=type=cache,target=/root/.cache/pip \
    pip3 install --break-system-packages --ignore-installed pip setuptools wheel gallery-dl
COPY --from=kepubify-builder /go/bin/kepubify /usr/bin/kepubify

FROM build-${TARGETARCH} AS runner
VOLUME /tmp
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
