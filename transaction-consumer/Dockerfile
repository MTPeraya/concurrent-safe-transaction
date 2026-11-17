FROM sbtscala/scala-sbt:eclipse-temurin-21_1.x AS builder

WORKDIR /build

COPY project/ project/
COPY build.sbt .
COPY src/ src/

RUN sbt clean assembly

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /build/target/scala-2.13/transaction-consumer-assembly-*.jar app.jar

ENV JAVA_OPTS="-Xms64m -Xmx256m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
