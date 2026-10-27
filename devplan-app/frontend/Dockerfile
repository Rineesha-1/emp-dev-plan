# Development plan frontend: the React/JSONForms app, built by Vite and served by nginx.
# nginx also forwards /api to the backend container, so the browser talks to one origin (no CORS).

# 1. Build the static files
FROM node:22.11.0-alpine AS build
WORKDIR /web
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . ./
RUN npm run build

# 2. Serve them
FROM nginx:1.27.3-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist /usr/share/nginx/html
EXPOSE 80
