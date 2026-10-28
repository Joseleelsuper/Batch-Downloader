FROM python:3.14-alpine AS ai-openapi

WORKDIR /workspace
RUN pip install --no-cache-dir PyYAML==6.0.3
COPY shared/contracts/openapi/batch-downloader-api.yaml shared/contracts/openapi/batch-downloader-api.yaml
COPY scripts/quality/generate_ai_openapi.py scripts/quality/generate_ai_openapi.py
RUN python scripts/quality/generate_ai_openapi.py --output /openapi.json

FROM node:24-alpine AS build

WORKDIR /app
ARG VITE_API_BASE_URL
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
COPY services/webapp/src/main/resources/frontend/package*.json ./
RUN npm install
COPY services/webapp/src/main/resources/frontend ./
COPY services/translation-service/locales /translation-service/locales
RUN npm run build

FROM nginx:1.27-alpine

COPY services/webapp/nginx.conf /etc/nginx/nginx.conf
COPY --chmod=755 services/webapp/docker-entrypoint.d/40-private-error-log.sh /docker-entrypoint.d/
COPY --from=build /app/dist /usr/share/nginx/html
COPY shared/contracts/openapi/batch-downloader-api.yaml /usr/share/nginx/html/openapi.yaml
COPY --from=ai-openapi /openapi.json /usr/share/nginx/html/openapi.json

EXPOSE 80 8080
