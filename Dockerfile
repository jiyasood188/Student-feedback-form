FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY index.html style.css script.js ./
RUN mvn -B clean package dependency:copy-dependencies -DoutputDirectory=target/dependency

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /app/target/classes ./target/classes
COPY --from=build /app/target/dependency ./target/dependency

ENV PORT=10000
EXPOSE 10000
CMD ["java", "-cp", "target/classes:target/dependency/*", "StudentFeedbackSystem", "--server"]
