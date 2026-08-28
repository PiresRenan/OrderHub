# OrderHub application image.
#
# Base images are pinned by immutable OCI index digest while retaining their
# human-readable Temurin version tags. Platform selection is performed by the
# build command so the Dockerfile is not unnecessarily tied to one CPU
# architecture.

FROM eclipse-temurin:21.0.12_8-jdk-noble@sha256:75ce56643243c3db632be2ef259625fb42ee3be1334389659f7a1a61acb78783 AS build

WORKDIR /workspace

# Maven Wrapper 3.3.4 expects an unzip implementation when consuming the
# repository-pinned ZIP distribution. Without it, the Unix wrapper falls back
# to the TAR.GZ distribution, whose bytes intentionally do not match the
# repository-pinned ZIP SHA-256.
#
# Pinning the package version makes changes to this build dependency explicit.
# This package exists only in the disposable builder stage and is never copied
# into the OrderHub runtime image.
ARG UNZIP_VERSION=6.0-28ubuntu4.1

RUN apt-get update \
    && apt-get install \
        --yes \
        --no-install-recommends \
        "unzip=${UNZIP_VERSION}" \
    && rm -rf /var/lib/apt/lists/*

# Copy dependency descriptors before application sources so source-only changes
# can reuse the Maven dependency layer/cache.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# The repository pins the Maven distribution and its SHA-256 through the Maven
# Wrapper. BuildKit retains downloaded Maven artifacts outside the final image.
RUN chmod 0555 mvnw

RUN --mount=type=cache,id=orderhub-maven,target=/root/.m2,sharing=locked \
    ./mvnw \
        --batch-mode \
        --no-transfer-progress \
        dependency:go-offline

# Production sources are copied only after dependency resolution.
COPY src/main/ src/main/

# Container packaging intentionally does not replace CI verification.
#
# Tests run through `mvn clean verify` before image publication. Running them
# inside `docker build` would later require exposing Docker/Testcontainers to
# the image builder and would incorrectly couple verification to packaging.
RUN --mount=type=cache,id=orderhub-maven,target=/root/.m2,sharing=locked \
    ./mvnw \
        --batch-mode \
        --no-transfer-progress \
        -Dmaven.test.skip=true \
        package \
    && jar_file="$(find target \
            -maxdepth 1 \
            -type f \
            -name 'orderhub-*.jar' \
            ! -name '*-sources.jar' \
            ! -name '*-javadoc.jar' \
            -print \
            -quit)" \
    && test -n "${jar_file}" \
    && cp "${jar_file}" application.jar \
    && java -Djarmode=tools \
        -jar application.jar \
        extract \
        --layers \
        --destination extracted


FROM eclipse-temurin:21.0.12_8-jre-noble@sha256:96975602e131485862eb8cd32927face8a06d7591a5e865944b634a701d9df72 AS runtime

ARG APP_VERSION=0.1.0-SNAPSHOT
ARG VCS_REF=development

LABEL org.opencontainers.image.title="OrderHub" \
      org.opencontainers.image.description="B2B multi-tenant order management platform" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.source="https://github.com/PiresRenan/OrderHub"

# Fixed UID/GID avoids depending on dynamically allocated identities and gives
# Kubernetes securityContext a stable identity to enforce later.
RUN groupadd \
        --system \
        --gid 10001 \
        orderhub \
    && useradd \
        --system \
        --uid 10001 \
        --gid 10001 \
        --no-create-home \
        --home-dir /nonexistent \
        --shell /usr/sbin/nologin \
        orderhub

WORKDIR /application

# Each Spring Boot logical layer becomes an OCI layer. Dependencies therefore
# remain reusable when only OrderHub application code changes.
COPY --from=build --chown=10001:10001 /workspace/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/application/ ./

USER 10001:10001

EXPOSE 8080

# Java remains PID 1 through exec-form ENTRYPOINT so Docker/Kubernetes SIGTERM
# reaches the JVM directly and can trigger Spring Boot's graceful shutdown.
STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-jar", "application.jar"]