FROM jenkins/ssh-agent:8.9.0-jdk21@sha256:32cbd440e4d665a2cbe77b9f79f948156dbd606447f400940300e115b9d35cb6

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends default-mysql-client git \
 && rm -rf /var/lib/apt/lists/*

COPY --chown=jenkins:jenkins build.gradle settings.gradle gradlew /opt/open-metadata-sync/
COPY --chown=jenkins:jenkins gradle /opt/open-metadata-sync/gradle
COPY --chown=jenkins:jenkins src/main /opt/open-metadata-sync/src/main
COPY --chown=jenkins:jenkins scripts/demo-mysql-client.sh \
    scripts/demo-install-sentinel.sql \
    scripts/demo-replay-fixture.sql \
    scripts/demo-replay-summary.sh \
    scripts/demo-reset-10k.sh \
    scripts/demo-reset-replay.sh \
    scripts/demo-verify-10k-no-op-ready.sh \
    /opt/open-metadata-sync/scripts/
ARG DEMO_REVISION=47461be71ae4add166b5a5ea157465c370894330
RUN printf '%s\n' "$DEMO_REVISION" > /opt/open-metadata-sync/.demo-revision \
 && chown jenkins:jenkins /opt/open-metadata-sync/.demo-revision

USER jenkins
WORKDIR /opt/open-metadata-sync
RUN ./gradlew --no-daemon bootJar

USER root
COPY docker/demo-jenkins/agent/demo-agent-entrypoint.sh /usr/local/bin/demo-agent-entrypoint
RUN chmod 0755 /usr/local/bin/demo-agent-entrypoint
ENTRYPOINT ["/usr/local/bin/demo-agent-entrypoint"]
