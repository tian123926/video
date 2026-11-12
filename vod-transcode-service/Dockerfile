FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/vod-transcode-service-*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
