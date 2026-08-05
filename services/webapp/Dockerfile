FROM node:20-alpine AS build

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
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
