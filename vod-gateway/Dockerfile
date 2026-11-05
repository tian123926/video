FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/vod-gateway-*.jar app.jar

EXPOSE 8088

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
