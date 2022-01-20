package com.elector.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.elector"})
public class ElectorDemoKubernetesApplication {

  public static void main(String[] args) {
    SpringApplication.run(ElectorDemoKubernetesApplication.class, args);
  }
}