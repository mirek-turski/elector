#FROM openjdk:11
FROM adoptopenjdk/openjdk11:alpine-slim
VOLUME /tmp
ARG JAR_FILE=target/*.jar
ARG JAVA_OPTS="-DSPRING_APPLICATION_NAME=elector-demo"
COPY ${JAR_FILE} app.jar
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app.jar"]
