# syntax=docker/dockerfile:1.7
#
# DeepGaze frontend — React 18 + Vite 5 built static, served by nginx.
#
# Two stages:
#   1. build    — node:20-alpine, runs `npm ci && npm run build` → /src/dist
#   2. runtime  — nginx:1.27-alpine, serves dist/ with a custom reverse
#                 proxy for /api (including SSE-safe config for /api/stream/*).

# ------------------------------------------------------------------
# Stage 1 — Vite build.
# ------------------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /src

# Deterministic install — the npm cache only busts when the lockfile changes.
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci

COPY . .
RUN npm run build

# ------------------------------------------------------------------
# Stage 2 — nginx static server + reverse proxy.
# ------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

# Our custom server block: SPA fallback, SSE-aware /api/stream/* proxy,
# aggressive caching for hashed Vite assets.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Static bundle.
COPY --from=build /src/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1

CMD ["nginx","-g","daemon off;"]
