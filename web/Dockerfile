# Sanchiti web UI: static files on nginx, which also forwards /api to the backend (same origin for the cookie).
FROM node:22-slim AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
# PORT: port to listen on (hosts like Render set it). API_URL: backend base URL, no trailing slash.
ENV PORT=8080 \
    API_URL=http://api:8787
EXPOSE 8080
