FROM eclipse-temurin:21-jdk AS builder
ARG SENTRY_AUTH_TOKEN
ENV SENTRY_AUTH_TOKEN=${SENTRY_AUTH_TOKEN}
RUN apt-get update && apt-get install -y maven
WORKDIR /operator
COPY java/common ./common
COPY java/operator ./operator
RUN cd /operator/common/maven-conf && \
    mvn clean install --no-transfer-progress && \
    cd /operator/common/org.eclipse.theia.cloud.common && \
    mvn clean install --no-transfer-progress && \
    cd /operator/operator/org.eclipse.theia.cloud.operator && \
    mvn clean install --no-transfer-progress && \
    cd /operator/operator/org.eclipse.theia.cloud.defaultoperator && \
    mvn clean verify --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
RUN mkdir /templates
WORKDIR /log-config
COPY java/operator/org.eclipse.theia.cloud.defaultoperator/log4j2.xml .
WORKDIR /operator
COPY --from=builder /operator/operator/org.eclipse.theia.cloud.defaultoperator/target/defaultoperator-1.2.0-SNAPSHOT-jar-with-dependencies.jar .
COPY java/operator/org.eclipse.theia.cloud.defaultoperator/sentry.properties .
RUN curl -L -o sentry-opentelemetry-agent-8.29.0.jar https://github.com/getsentry/sentry-java/releases/download/8.29.0/sentry-opentelemetry-agent-8.29.0.jar

ENV SENTRY_PROPERTIES_FILE=sentry.properties
# to get more debug information from the kubernetes client itself, add -Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG below
ENTRYPOINT [ "java", "-javaagent:sentry-opentelemetry-agent-8.29.0.jar", "-Dlog4j2.configurationFile=/log-config/log4j2.xml", "-jar", "./defaultoperator-1.2.0-SNAPSHOT-jar-with-dependencies.jar" ]
CMD [ "" ]
