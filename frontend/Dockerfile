# The frontend is a browser-side SPA: fetch() calls made in the user's browser hit backend URLs
# directly (VITE_*_SERVICE_URL, baked in at build time — see .env.example). Those default to
# http://localhost:808X, which stays correct even when this container's HTML/JS is served from a
# different container, because docker-compose.yml publishes every backend's port to the host and
# the browser talks to the host, not to the Docker network. So this image only needs to build the
# static assets and serve them — no reverse proxy, no runtime awareness of the Docker network.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
