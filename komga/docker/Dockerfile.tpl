FROM eclipse-temurin:17-jre as builder
ARG JAR={{distributionArtifactFile}}
WORKDIR /builder
COPY assembly/${JAR} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Build kepubify from source with current Go
FROM golang:1.24 as kepubify-builder
RUN go install github.com/pgaskin/kepubify/v4/cmd/kepubify@latest

# amd64 builder
FROM ubuntu:24.10 as build-amd64
ENV JAVA_HOME=/opt/java/openjdk
COPY --from=eclipse-temurin:23-jre $JAVA_HOME $JAVA_HOME
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN sed -i -re 's/([a-z]{2}\.)?archive.ubuntu.com|security.ubuntu.com/old-releases.ubuntu.com/g' /etc/apt/sources.list.d/ubuntu.sources && \
    apt -y update && \
    apt -y install ca-certificates locales libjxl-dev libheif-dev libwebp-dev libarchive-dev wget curl python3 python3-pip && \
    echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen && \
    locale-gen en_US.UTF-8 && \
    pip3 install --break-system-packages gallery-dl && \
    apt -y autoremove && rm -rf /var/lib/apt/lists/*
COPY --from=kepubify-builder /go/bin/kepubify /usr/bin/kepubify
ENV LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/lib/x86_64-linux-gnu"

# arm64 builder
FROM ubuntu:24.10 as build-arm64
ENV JAVA_HOME=/opt/java/openjdk
COPY --from=eclipse-temurin:23-jre $JAVA_HOME $JAVA_HOME
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN sed -i -re 's/([a-z]{2}\.)?ports.ubuntu.com\/ubuntu-ports/old-releases.ubuntu.com\/ubuntu/g' /etc/apt/sources.list.d/ubuntu.sources && \
    apt -y update && \
    apt -y install ca-certificates locales libjxl-dev libheif-dev libwebp-dev libarchive-dev wget curl python3 python3-pip && \
    echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen && \
    locale-gen en_US.UTF-8 && \
    pip3 install --break-system-packages gallery-dl && \
    apt -y autoremove && rm -rf /var/lib/apt/lists/*
COPY --from=kepubify-builder /go/bin/kepubify /usr/bin/kepubify
ENV LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/lib/aarch64-linux-gnu"

# arm builder: uses temurin-17, as arm32 support was dropped in JDK 21
FROM eclipse-temurin:17-jre as build-arm
RUN apt -y update && \
    apt -y install wget curl python3 python3-pip && \
    pip3 install --break-system-packages gallery-dl && \
    apt -y autoremove && rm -rf /var/lib/apt/lists/*
COPY --from=kepubify-builder /go/bin/kepubify /usr/bin/kepubify

FROM build-${TARGETARCH} AS runner
VOLUME /tmp
VOLUME /config
WORKDIR app
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./
ENV KOMGA_CONFIGDIR="/config"
ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'
ENTRYPOINT ["java", "-Dspring.profiles.include=docker", "--enable-native-access=ALL-UNNAMED", "-jar", "application.jar", "--spring.config.additional-location=file:/config/"]
EXPOSE 25600
LABEL org.opencontainers.image.source="https://github.com/gotson/komga"
