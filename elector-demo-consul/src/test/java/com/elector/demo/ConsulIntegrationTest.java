package com.elector.demo;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.WaitingConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class ConsulIntegrationTest {

  @Data
  public static class InstanceInfoData {
    private final String id;
    private final String host;
    private String state;
    private int order;
    private final long weight;
    InstanceInfoData (
        @JsonProperty("id") String id,
        @JsonProperty("host") String host,
        @JsonProperty("state") String state,
        @JsonProperty("order") int order,
        @JsonProperty("weight") long weight) {
      this.id = id;
      this.host = host;
      this.state = state;
      this.order = order;
      this.weight = weight;
    }
  }

  @Data
  public static class Instances {

    private InstanceInfoData self;
    private Map<String, InstanceInfoData> peers;
  }

  @Data
  public static class InfoData {

    private Instances instances;
  }

  public static class LogPredicate implements Predicate<OutputFrame> {

    public static LogPredicate INSTANCE = new LogPredicate();

    @Override
    public boolean test(OutputFrame outputFrame) {
      var entry = outputFrame.getUtf8String();
      return entry.contains("This InstanceInfo");
    }
  }

  private final static RestTemplate restTemplate;

  static {
    restTemplate = new RestTemplate();
    var converter = new MappingJackson2HttpMessageConverter();
    restTemplate.setMessageConverters(List.of(converter));


    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    context.getLogger("ROOT").setLevel(Level.INFO);
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

  public static WaitingConsumer logConsumer1 = new WaitingConsumer();
  public static WaitingConsumer logConsumer2 = new WaitingConsumer();
  public static WaitingConsumer logConsumer3 = new WaitingConsumer();

  @Container
  public static DockerComposeContainer environment =
      new DockerComposeContainer(new File("docker-compose.yml"))
          .withExposedService("consul_1", 8500, Wait.forListeningPort())
          .withExposedService("elector-demo-consul_1", 8080,
              Wait.forHttp("/actuator/health").forStatusCode(200))
          .withExposedService("elector-demo-consul_2", 8080,
              Wait.forHttp("/actuator/health").forStatusCode(200))
          .withExposedService("elector-demo-consul_3", 8080,
              Wait.forHttp("/actuator/health").forStatusCode(200))
          .withLogConsumer("elector-demo-consul_1", logConsumer1)
          .withLogConsumer("elector-demo-consul_2", logConsumer2)
          .withLogConsumer("elector-demo-consul_3", logConsumer3);

  @Test
  public void testElectorRoutinesResult() throws JsonProcessingException, TimeoutException {

    // Wait until all instances log 'This InstanceInfo' log message indicating end of elector routines
    logConsumer1.waitUntil(LogPredicate.INSTANCE, 30, TimeUnit.SECONDS);
    logConsumer2.waitUntil(LogPredicate.INSTANCE, 30, TimeUnit.SECONDS);
    logConsumer3.waitUntil(LogPredicate.INSTANCE, 30, TimeUnit.SECONDS);

    // Get instances info reported by all services and verify they re the same
    var info1 = restTemplate.getForObject(getInstanceAddress("elector-demo-consul_1") + "/actuator/info",
        InfoData.class);
    var instances1 = getInstances(info1);
    var info2 = restTemplate.getForObject(getInstanceAddress("elector-demo-consul_2") + "/actuator/info",
        InfoData.class);
    var instances2 = getInstances(info2);
    assertThat(instances2).hasSameElementsAs(instances1);
    var info3 = restTemplate.getForObject(getInstanceAddress("elector-demo-consul_3") + "/actuator/info",
        InfoData.class);
    var instances3 = getInstances(info3);
    assertThat(instances3).hasSameElementsAs(instances2);

    // TODO: Assert the configuration of the instances after election
  }

  private List<InstanceInfoData> getInstances(InfoData info) {
    assertThat(info).isNotNull();
    assertThat(info.getInstances()).isNotNull();
    assertThat(info.getInstances().getSelf()).isNotNull();
    assertThat(info.getInstances().getPeers()).isNotEmpty();
    var instances = new ArrayList<>(info.getInstances().getPeers().values());
    instances.add(info.getInstances().getSelf());
    return instances;
  }

  private String getInstanceAddress(String instanceName) {
    return String.format("http://%s:%d",
        environment.getServiceHost(instanceName, 8080),
        environment.getServicePort(instanceName, 8080));
  }

}
