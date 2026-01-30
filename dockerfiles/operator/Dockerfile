FROM eclipse-temurin:21-jdk AS builder
# Accept APT_HTTP_PROXY build-arg for caching
ARG APT_HTTP_PROXY
# Configure apt proxy if provided (hybrid: works on ARC and GitHub runners)
RUN if [ -n "$APT_HTTP_PROXY" ]; then \
      echo "Acquire::http::Proxy \"$APT_HTTP_PROXY\";" > /etc/apt/apt.conf.d/01proxy; \
      echo "Using APT Proxy: $APT_HTTP_PROXY"; \
    else \
      echo "No APT Proxy set, using direct connection"; \
    fi
RUN apt-get update && apt-get install -y maven
WORKDIR /operator
COPY java/common ./common
COPY java/operator ./operator
RUN --mount=type=secret,id=SENTRY_AUTH_TOKEN,required=true \
    export SENTRY_AUTH_TOKEN="$(cat /run/secrets/SENTRY_AUTH_TOKEN)" && \
    cd /operator/common/maven-conf && \
    mvn clean install --no-transfer-progress && \
    cd /operator/common/org.eclipse.theia.cloud.common && \
    mvn clean install --no-transfer-progress && \
    cd /operator/operator/org.eclipse.theia.cloud.operator && \
    mvn clean install --no-transfer-progress && \
    cd /operator/operator/org.eclipse.theia.cloud.defaultoperator && \
    mvn clean verify --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl unzip
RUN mkdir /templates
WORKDIR /log-config
COPY java/operator/org.eclipse.theia.cloud.defaultoperator/log4j2.xml .
WORKDIR /operator
COPY --from=builder /operator/operator/org.eclipse.theia.cloud.defaultoperator/target/defaultoperator-1.2.0-SNAPSHOT-jar-with-dependencies.jar .
COPY java/operator/org.eclipse.theia.cloud.defaultoperator/sentry.properties .
RUN curl -fsSL -o sentry-agent.zip https://github.com/getsentry/sentry-java/releases/download/8.29.0/sentry-opentelemetry-agent-8.29.0.zip && \
    unzip -j sentry-agent.zip -d . && \
    rm sentry-agent.zip

ENV SENTRY_PROPERTIES_FILE=/operator/sentry.properties
# to get more debug information from the kubernetes client itself, add -Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG below
ENTRYPOINT ["java", "-javaagent:sentry-opentelemetry-agent-8.29.0.jar", \
    "-Dsentry.properties.file=/operator/sentry.properties", \
    "-Dlog4j2.configurationFile=/log-config/log4j2.xml", \
    "-jar", "./defaultoperator-1.2.0-SNAPSHOT-jar-with-dependencies.jar"]
