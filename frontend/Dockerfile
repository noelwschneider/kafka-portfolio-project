# The frontend is a browser-side SPA: fetch() calls made in the user's browser hit backend URLs
# baked in at build time (VITE_*_SERVICE_URL — see .env.example and src/api/client.ts). This image
# is built two different ways, both via the same Dockerfile using the ARGs below:
#
#   - Local Compose / kind (the default — build with no --build-arg overrides): the ARGs default to
#     http://localhost:808X, which stays correct because docker-compose.yml publishes every
#     backend's port to the host, and infrastructure/kind-config.yaml maps those same host ports for
#     kind — either way the browser talks to the host, not the container/pod network.
#   - Production build (deployed behind a single ingress — see
#     docs/agent-reports/sprint-2/deployment-code-changes-briefing.md's W1/W2): pass each ARG as a
#     relative same-origin prefix, e.g. `--build-arg VITE_ORDER_SERVICE_URL=/svc/order`.
#     apiFetch and both EventSource URLs in src/api/client.ts concatenate `${baseUrl}${path}`
#     unchanged either way, so a relative prefix works with no other code change.
#
# nginx.conf below adds the SPA try_files fallback the stock nginx:alpine image doesn't have (a
# deep link or refresh on a client-side route 404s without it — see App.tsx's BrowserRouter).
FROM node:22-alpine AS build
WORKDIR /app
ARG VITE_ORDER_SERVICE_URL=http://localhost:8081
ARG VITE_INVENTORY_SERVICE_URL=http://localhost:8082
ARG VITE_PAYMENT_SERVICE_URL=http://localhost:8083
ARG VITE_FULFILLMENT_SERVICE_URL=http://localhost:8084
ARG VITE_SCENARIO_SERVICE_URL=http://localhost:8085
ENV VITE_ORDER_SERVICE_URL=$VITE_ORDER_SERVICE_URL \
    VITE_INVENTORY_SERVICE_URL=$VITE_INVENTORY_SERVICE_URL \
    VITE_PAYMENT_SERVICE_URL=$VITE_PAYMENT_SERVICE_URL \
    VITE_FULFILLMENT_SERVICE_URL=$VITE_FULFILLMENT_SERVICE_URL \
    VITE_SCENARIO_SERVICE_URL=$VITE_SCENARIO_SERVICE_URL
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
