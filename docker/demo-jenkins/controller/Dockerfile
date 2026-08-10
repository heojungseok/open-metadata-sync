FROM jenkins/jenkins:2.576-jdk21@sha256:8c1c7e28b463e8dd93b01d5b1853e58c95e01214693355eeff8084b04dccd0a3

ARG DEMO_INFRA_REVISION
LABEL org.opencontainers.image.revision="$DEMO_INFRA_REVISION"

ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.model.DirectoryBrowserSupport.CSP=default-src\ 'none'"

COPY docker/demo-jenkins/controller/plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY Jenkinsfile.demo /opt/demo-pipelines/Jenkinsfile.demo
COPY Jenkinsfile.demo-live-crossref /opt/demo-pipelines/Jenkinsfile.demo-live-crossref
COPY Jenkinsfile.crossref /opt/demo-pipelines/Jenkinsfile.crossref
COPY docker/demo-jenkins/controller/init.groovy.d/security-and-jobs.groovy \
    /usr/share/jenkins/ref/init.groovy.d/security-and-jobs.groovy.override
COPY --chown=root:root --chmod=0755 docker/demo-jenkins/controller/demo-bootstrap-jenkins-home.sh \
    /usr/local/bin/demo-bootstrap-jenkins-home
