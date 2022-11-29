package com.elector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.jupiter.api.Test;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class ContainerTest {

  @Container
  private static final ConsulContainer CONSUL_CONTAINER = new ConsulContainer("consul");

  @Container
  public static GenericContainer<?> simpleWebServer
      = new GenericContainer<>("alpine:3.2")
      .withExposedPorts(80)
      .withCommand("/bin/sh", "-c", "while true; do echo "
          + "\"HTTP/1.1 200 OK\n\nHello World!\" | nc -l -p 80; done");

  public static DockerComposeContainer compose =
      new DockerComposeContainer();

  @Test
  public void test() throws Exception {
    String address = String.format("http://%s:%d",
        simpleWebServer.getHost(), simpleWebServer.getMappedPort(80));
    String response = simpleGetRequest(address);
    assertEquals(response, "Hello World!");
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
