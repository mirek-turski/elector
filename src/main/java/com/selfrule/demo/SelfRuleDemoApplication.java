package com.selfrule.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.selfrule"})
public class SelfRuleDemoApplication {

  public static void main(String[] args) {
    SpringApplication.run(SelfRuleDemoApplication.class, args);
  }
}
