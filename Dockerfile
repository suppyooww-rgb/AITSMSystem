FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace
COPY gradle ./gradle
COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts gradle.properties gradle.lockfile ./
COPY .env.example .gitignore ./
COPY .github ./.github
RUN chmod +x gradlew
COPY src ./src
COPY db ./db
COPY collector ./collector
RUN ./gradlew --no-daemon clean test buildFatJar

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S -g 10001 aitsm && adduser -S -D -H -u 10001 -G aitsm aitsm
WORKDIR /app
COPY --from=build /workspace/build/libs/aitsm-all.jar /app/aitsm.jar
RUN mkdir -p /app/uploads/profile-photos && chown -R aitsm:aitsm /app

ENV PORT=8070 \
    APP_ENV=production \
    PROFILE_UPLOAD_DIR=/app/uploads/profile-photos
EXPOSE 8070
VOLUME ["/app/uploads"]

USER aitsm
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O - "http://127.0.0.1:${PORT}/api/health" >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/aitsm.jar"]
