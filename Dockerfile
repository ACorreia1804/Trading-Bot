FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q package -DskipTests && mvn -q dependency:copy-dependencies -DoutputDirectory=target/libs

FROM eclipse-temurin:17-jre
COPY --from=build /app/target/trading-bot-1.0.jar /app/app.jar
COPY --from=build /app/target/libs /app/libs
WORKDIR /data
CMD ["java", "-cp", "/app/app.jar:/app/libs/*", "Bot"]