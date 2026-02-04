FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copiar arquivos do projeto
COPY services/backend-api/pom.xml .
COPY services/backend-api/src ./src

# Compilar o projeto
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]