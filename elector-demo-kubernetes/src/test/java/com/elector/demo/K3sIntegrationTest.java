package com.elector.demo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.RbacAuthorizationV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Role;
import io.kubernetes.client.openapi.models.V1RoleBinding;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Yaml;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.images.builder.ImageFromDockerfile;
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
  public GenericContainer<?> electorContainer = new GenericContainer<>(
      new ImageFromDockerfile("elector-demo")
          .withDockerfile(Path.of("./Dockerfile")));

  @Container
  public static K3sContainer k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.21.3-k3s1"))
      .withLogConsumer(new Slf4jLogConsumer(log));

  @Test
  public void test() throws IOException, ApiException {
    var kubeConfigYaml = k3s.getKubeConfigYaml();
    var client = Config.fromConfig(new StringReader(kubeConfigYaml));

    Configuration.setDefaultApiClient(client);

    var rbacApi = new RbacAuthorizationV1Api();
    var coreApi = new CoreV1Api();
    var appsApi = new AppsV1Api();

    // Use rbac.authorization.k8s.io/v1 to create namespace-reader Role and its binding with the default service account
    var updateAccountYaml = Paths.get("update-default-service-account.yml").toAbsolutePath();
    var configs = Yaml.loadAll(updateAccountYaml.toFile());
    var role = configs.stream().filter(o -> o instanceof V1Role).map(o -> (V1Role)o).findFirst()
        .orElseThrow(() -> new RuntimeException("No V1Role in update-default-service-account.yml"));
    var roleBinding = configs.stream().filter(o -> o instanceof V1RoleBinding).map(o -> (V1RoleBinding)o).findFirst()
        .orElseThrow(() -> new RuntimeException("No V1RoleBinding in update-default-service-account.yml"));
    rbacApi.createNamespacedRole("default", role, null, null, null, null);
    rbacApi.createNamespacedRoleBinding("default", roleBinding, null, null, null, null);

    // Use core V1 API to create service
    var deploymentYaml = Paths.get("elector-demo-deployment.yml").toAbsolutePath();
    configs = Yaml.loadAll(deploymentYaml.toFile());
    var service = configs.stream().filter(o -> o instanceof V1Service).map(o -> (V1Service)o).findFirst()
        .orElseThrow(() -> new RuntimeException("No V1Service in elector-demo-deployment.yml"));
    coreApi.createNamespacedService("default", service, null, null, null, null);

    // Use Apps V1 API to create deployment
    var deployment = configs.stream().filter(o -> o instanceof V1Deployment).map(o -> (V1Deployment)o).findFirst()
        .orElseThrow(() -> new RuntimeException("No V1Deployment in elector-demo-deployment.yml"));
    appsApi.createNamespacedDeployment("default", deployment, null, null, null, null);

    // Get the pods
    var podList = coreApi.listNamespacedPod("default", null, true, null, null, null, 100, null, null, 5, false);

    var pods = podList.getItems();

    // TODO: Query the pods for their status

    // See https://github.com/kubernetes-client/java/issues/170 and
    // https://www.testcontainers.org/features/creating_images/
    // See: https://github.com/kubernetes-client/java/blob/master/examples/examples-release-13/src/main/java/io/kubernetes/client/examples/YamlExample.java

  }
}
