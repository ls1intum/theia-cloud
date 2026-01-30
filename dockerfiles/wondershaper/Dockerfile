FROM debian:bookworm-slim
# Copy apt proxy config if available (non-fatal if missing)
RUN if [ -f .docker-build-config/01proxy ]; then \
      cp .docker-build-config/01proxy /etc/apt/apt.conf.d/; \
    fi
RUN apt-get update && apt-get install -y iproute2 wondershaper && \
    apt-get clean
# kilobits per second
ENV DOWNLINK 4000
ENV UPLINK 4000
ENTRYPOINT wondershaper eth0 ${DOWNLINK} ${UPLINK} || true
CMD [ "" ]