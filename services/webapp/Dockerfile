FROM node:20-alpine AS build

WORKDIR /app
COPY src/main/resources/frontend/package.json ./
RUN npm install
COPY src/main/resources/frontend ./
RUN npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
