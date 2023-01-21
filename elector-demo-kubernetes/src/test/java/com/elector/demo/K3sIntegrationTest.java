package com.elector.demo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1NodeList;
import io.kubernetes.client.util.Config;
import java.io.IOException;
import java.io.StringReader;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Slf4j
public class K3sIntegrationTest {

  static {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    context.getLogger("ROOT").setLevel(Level.ERROR);
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setPattern("%date{HH:mm:ss.SSS} %5level{0} [%-15.15thread] %-30.30logger{39} : %msg%n");
    encoder.setContext(context);
    encoder.start();

    ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
    consoleAppender.setEncoder(encoder);
    consoleAppender.setContext(context);
    consoleAppender.start();

    Logger logger = (Logger) LoggerFactory.getLogger("com.elector.demo");
    logger.addAppender(consoleAppender);
    logger.setLevel(Level.DEBUG);
    logger.setAdditive(false); /* set to true if root should log too */
  }

  @Container
  public static K3sContainer k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.21.3-k3s1"))
      .withLogConsumer(new Slf4jLogConsumer(log));

  @Test
  public void test() throws IOException, ApiException {
    String kubeConfigYaml = k3s.getKubeConfigYaml();

    ApiClient client = Config.fromConfig(new StringReader(kubeConfigYaml));
    CoreV1Api api = new CoreV1Api(client);

    // interact with the running K3s server, e.g.:
    V1NodeList nodes = api.listNode(null, null, null, null, null, null, null, null, null, null);

    // See https://github.com/kubernetes-client/java/issues/170 and
    // https://www.testcontainers.org/features/creating_images/

  }
}
