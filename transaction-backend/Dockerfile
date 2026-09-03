# STAGE 1: Build the Application
FROM maven:3.9-eclipse-temurin-21 AS builder
# Java version 21
WORKDIR /build

# Copy build files to leverage Docker layer caching for dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build the package
COPY src ./src
RUN mvn clean package -DskipTests

# STAGE 2: Create the Runtime Image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Security Best Practice: Create a non-root user to run the app safely
RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

# Copy only the compiled JAR from the builder stage
COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
