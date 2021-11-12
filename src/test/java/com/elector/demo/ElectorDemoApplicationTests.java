package com.elector.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"server.port=0", "spring.cloud.kubernetes.enabled=false"})
class ElectorDemoApplicationTests {

	@Test
	void contextLoads() {
	}

}
