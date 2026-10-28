FROM node:24-alpine AS build

WORKDIR /app
ARG VITE_API_BASE_URL
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
COPY services/webapp/src/main/resources/frontend/package*.json ./
RUN npm ci
COPY services/webapp/src/main/resources/frontend ./
COPY services/translation-service/locales /translation-service/locales
RUN npm run build

FROM nginx:1.30.6-alpine

COPY services/webapp/nginx.conf /etc/nginx/nginx.conf
COPY --chmod=755 services/webapp/docker-entrypoint.d/40-private-error-log.sh /docker-entrypoint.d/
COPY --from=build /app/dist /usr/share/nginx/html
COPY shared/contracts/openapi/batch-downloader-api.yaml /usr/share/nginx/html/openapi.yaml
COPY shared/contracts/openapi/batch-downloader-ai-api.json /usr/share/nginx/html/openapi.json

EXPOSE 80 8080
