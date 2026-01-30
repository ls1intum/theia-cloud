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
WORKDIR /conversion
COPY java/common ./common
COPY java/conversion ./conversion
RUN cd /conversion/common/maven-conf && \
    mvn clean install --no-transfer-progress && \
    cd /conversion/common/org.eclipse.theia.cloud.common && \
    mvn clean install --no-transfer-progress&& \
    cd /conversion/conversion/org.eclipse.theia.cloud.conversion && \
    mvn clean package -Dmaven.test.skip=true -Dquarkus.package.type=uber-jar --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
WORKDIR /conversion
COPY --from=builder /conversion/conversion/org.eclipse.theia.cloud.conversion/target/conversion-webhook-1.2.0-SNAPSHOT-runner.jar .

ENV CERT_RELOAD_PERIOD=604800

ENTRYPOINT ["java", "-Dquarkus.http.ssl.certificate.reload-period=${CERT_RELOAD_PERIOD}", "-jar", "./conversion-webhook-1.2.0-SNAPSHOT-runner.jar"]
CMD [ "" ]
