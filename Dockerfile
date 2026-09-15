# syntax=docker/dockerfile:1

# ---- Stage 1: compile the TypeScript frontend into backend/web/js ----
FROM node:20-slim AS frontend-build
WORKDIR /repo
COPY frontend/package.json frontend/package-lock.json frontend/
RUN cd frontend && npm ci
COPY frontend/tsconfig.json frontend/
COPY frontend/src frontend/src
RUN cd frontend && npm run build
# tsconfig's outDir ("../backend/web/js") now exists at /repo/backend/web/js

# ---- Stage 2: compile the Java backend ----
FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /repo
COPY backend/src backend/src
COPY backend/libs backend/libs
RUN mkdir -p backend/out \
 && javac -cp backend/libs/sqlite-jdbc-3.53.4.0.jar -d backend/out $(find backend/src -name "*.java")

# ---- Stage 3: minimal runtime image ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY backend/libs ./libs
COPY backend/web/index.html ./web/index.html
COPY backend/web/css ./web/css
COPY --from=frontend-build /repo/backend/web/js ./web/js
COPY --from=backend-build /repo/backend/out ./out

ENV WEB_ROOT=web
ENV DB_PATH=data/events.db
# Render (and most PaaS hosts) inject PORT at runtime; Main.java already reads it.
EXPOSE 8080

CMD ["java", "-cp", "out:libs/sqlite-jdbc-3.53.4.0.jar", "com.laurier.events.Main"]
