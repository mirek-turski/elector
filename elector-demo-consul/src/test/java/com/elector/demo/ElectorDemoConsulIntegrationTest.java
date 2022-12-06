package com.elector.demo;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class ElectorDemoConsulIntegrationTest {

  @Container
  public static DockerComposeContainer environment =
      new DockerComposeContainer(new File("docker-compose.yml"))
          .withExposedService("consul_1", 8500, Wait.forListeningPort())
          .withExposedService("elector-demo-consul_1", 8080,
              Wait.forHttp("/actuator/health").forStatusCode(200))
          .withExposedService("elector-demo-consul_2", 8080,
              Wait.forHttp("/actuator/health").forStatusCode(200))
          .withExposedService("elector-demo-consul_3", 8080,
              Wait.forHttp("/actuator/health").forStatusCode(200));

  @Test
  public void test() throws Exception {
    String address = String.format("http://%s:%d/actuator/info",
        environment.getServiceHost("elector-demo-consul_1", 8080),
        environment.getServicePort("elector-demo-consul_1", 8080));
    String response = simpleGetRequest(address);
    System.out.println(response);
//    assertEquals(response, "Hello World!");
  }

  private String simpleGetRequest(String address) throws Exception {
    URL url = new URL(address);
    HttpURLConnection con = (HttpURLConnection) url.openConnection();
    con.setRequestMethod("GET");

    StringBuilder content = new StringBuilder();
    String inputLine;
    try (BufferedReader in = new BufferedReader(
        new InputStreamReader(con.getInputStream()))) {
      while ((inputLine = in.readLine()) != null) {
        content.append(inputLine);
      }

    }

    return content.toString();
  }

}
