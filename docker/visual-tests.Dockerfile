# Match @playwright/test exactly; update image and package together.
FROM node:24-bookworm-slim AS node
FROM mcr.microsoft.com/playwright:v1.63.0-noble
COPY --from=node /usr/local/ /opt/node/
ENV PATH="/opt/node/bin:${PATH}"
WORKDIR /workspace/frontend
