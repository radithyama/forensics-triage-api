# syntax=docker/dockerfile:1.7

# ---- Build: static export of the Next.js app ----
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# ---- Runtime: unprivileged nginx serves the static files and proxies /api to the gateway ----
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/out /usr/share/nginx/html
# Gateway origin (e.g. https://api-gateway-123.asia-southeast2.run.app), rendered into /config.js and
# the CSP at container start by the image's envsubst entrypoint.
ENV API_BASE_URL=http://localhost:8080
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
