FROM openjdk:11
VOLUME /tmp
ARG JAR_FILE=target/*.jar
ENV JAVA_OPTS="-Dserver.port=8080 \
-Dspring.cloud.kubernetes.enabled=true \
-Dspring.cloud.kubernetes.discovery.enabled=true \
-Dinstance.heartbeat-interval-millis=1000 \
-Dinstance.heartbeat-timeout-millis=2000 \
-Dinstance.pool-size=4 \
-Dlogging.level.com.elector=DEBUG"
COPY ${JAR_FILE} app.jar
ENTRYPOINT exec java ${JAVA_OPTS} -jar /app.jar
