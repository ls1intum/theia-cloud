FROM debian:bookworm-slim
# Accept APT_HTTP_PROXY build-arg for caching
ARG APT_HTTP_PROXY
# Configure apt proxy if provided (hybrid: works on ARC and GitHub runners)
RUN if [ -n "$APT_HTTP_PROXY" ]; then \
      echo "Acquire::http::Proxy \"$APT_HTTP_PROXY\";" > /etc/apt/apt.conf.d/01proxy; \
      echo "Using APT Proxy: $APT_HTTP_PROXY"; \
    else \
      echo "No APT Proxy set, using direct connection"; \
    fi
RUN apt-get update && apt-get install -y iproute2 wondershaper && \
    apt-get clean
# kilobits per second
ENV DOWNLINK 4000
ENV UPLINK 4000
ENTRYPOINT wondershaper eth0 ${DOWNLINK} ${UPLINK} || true
CMD [ "" ]