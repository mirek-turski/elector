package com.elector.demo;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.elector.InstanceInfo;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "server.port=0",
      "spring.cloud.elector.enabled=true",
      "spring.cloud.elector.instance-id=test",
      "spring.cloud.elector.pool-size=1",
      "spring.cloud.elector.ballot-timeout-millis=300",
      "spring.cloud.elector.ballot-type=quorum",
      "logging.level.com.elector=debug",
      "spring.cloud.consul.enabled=false"
    })
class ElectorDemoConsulApplicationTest {

	@Autowired
	private InstanceInfo selfInfo;

	@Test
	void testSelfInfo() {
		await().atMost(3000, TimeUnit.MILLISECONDS).until(() -> selfInfo.isActive());
		assertEquals(1, selfInfo.getOrder());
	}

}
