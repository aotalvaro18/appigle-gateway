FROM openjdk:17-slim as builder
WORKDIR /app
COPY . .
RUN ./mvnw clean package -DskipTests

FROM openjdk:17-slim
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
COPY docker-entrypoint.sh /
RUN chmod +x /docker-entrypoint.sh
EXPOSE 8092
ENTRYPOINT ["/docker-entrypoint.sh", "java", "-jar", "app.jar", "--spring.profiles.active=azure"]