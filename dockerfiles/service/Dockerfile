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
WORKDIR /service
COPY java/common ./common
COPY java/service ./service
RUN --mount=type=secret,id=SENTRY_AUTH_TOKEN,required=true \
    export SENTRY_AUTH_TOKEN="$(cat /run/secrets/SENTRY_AUTH_TOKEN)" && \
    cd /service/common/maven-conf && \
    mvn clean install --no-transfer-progress && \
    cd /service/common/org.eclipse.theia.cloud.common && \
    mvn clean install --no-transfer-progress&& \
    cd /service/service/org.eclipse.theia.cloud.service && \
    mvn clean package -Dmaven.test.skip=true -Dquarkus.package.type=uber-jar --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl unzip
WORKDIR /service
COPY --from=builder /service/service/org.eclipse.theia.cloud.service/target/service-1.2.0-SNAPSHOT-runner.jar .
COPY java/service/org.eclipse.theia.cloud.service/sentry.properties .
RUN curl -fsSL -o sentry-agent.zip https://github.com/getsentry/sentry-java/releases/download/8.29.0/sentry-opentelemetry-agent-8.29.0.zip && \
    unzip -j sentry-agent.zip -d . && \
    rm sentry-agent.zip

ENV SERVICE_PORT=8081

ENV APPID=default-app-id

ENV KEYCLOAK_ENABLE=true
# Keycloak config
ENV KEYCLOAK_SERVERURL=https://keycloak.url/auth/realms/TheiaCloud
ENV KEYCLOAK_CLIENTID=theia-cloud
ENV KEYCLOAK_CLIENTSECRET=publicbutoauth2proxywantsasecret
ENV KEYCLOAK_ADMIN_GROUP=theia-cloud/admin

# Use SERVICE_AUTH_TOKEN if set, otherwise fall back to APPID for backwards compatibility
ENV SENTRY_PROPERTIES_FILE=/service/sentry.properties
ENTRYPOINT java -javaagent:sentry-opentelemetry-agent-8.29.0.jar \
    -Dsentry.properties.file=${SENTRY_PROPERTIES_FILE} \
    -Dtheia.cloud.service.auth.token=${SERVICE_AUTH_TOKEN:-${APPID}} \
    -Dquarkus.http.port=${SERVICE_PORT} \
    -Dtheia.cloud.auth.admin.group=${KEYCLOAK_ADMIN_GROUP} \
    -Dtheia.cloud.use.keycloak=${KEYCLOAK_ENABLE} \
    -Dquarkus.oidc.auth-server-url=${KEYCLOAK_SERVERURL} \
    -Dquarkus.oidc.client-id=${KEYCLOAK_CLIENTID} \
    -Dquarkus.oidc.credentials.secret=${KEYCLOAK_CLIENTSECRET} \
    -jar ./service-1.2.0-SNAPSHOT-runner.jar
CMD [ "" ]
