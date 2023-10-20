package com.elector;

import static com.elector.Constant.ORDER_UNASSIGNED;
import static com.elector.Constant.STATE_ACTIVE;
import static com.elector.Constant.STATE_DISCOVERED;
import static com.elector.Constant.STATE_INTRODUCED;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.lang.NonNull;

public class InstanceRegistryTest {

  private final ElectorProperties properties = new ElectorProperties();

  private final InstanceRegistry registry = new InstanceRegistry(properties, null, null);

  InstanceInfo self =
      InstanceInfo.builder().id("self").weight(1233L).order(ORDER_UNASSIGNED).state(STATE_INTRODUCED).build();

  InstanceInfo introducedInstance =
      InstanceInfo.builder().id("introduced").weight(1234L).order(ORDER_UNASSIGNED).state(STATE_INTRODUCED).build();

  @Test
  public void testResolveOrderInvalidCandidateState() {
    assertThat(registry.resolveOrder(self.toBuilder().order(12).state(STATE_ACTIVE).build())).isEqualTo(12);
    assertThat(registry.resolveOrder(self.toBuilder().state(STATE_DISCOVERED).build())).isEqualTo(0);
  }

  @Test
  public void testResolveOrderSelfNoPeers() {
    properties.setPoolSize(1);
    assertOrder(1, self, self, null, null);
  }

  @Test
  public void testResolveOrderSelfReadyPeerIntroduced() {
    properties.setPoolSize(2);
    assertOrder(2, self.toBuilder().order(1).state(STATE_ACTIVE).build(), introducedInstance, null, null);
    assertOrder(1, self.toBuilder().order(2).state(STATE_ACTIVE).build(), introducedInstance, null, null);
  }

  @Test
  public void testResolveOrderSelfDiscoveredPeer() {
    properties.setPoolSize(2);
    final InstanceInfo discoveredInstance =
        InstanceInfo.builder().id("self").weight(0).order(0).state(STATE_DISCOVERED).build();
    assertOrder(1, self, self, List.of(discoveredInstance), null);
  }

  @Test
  public void testResolveOrderSelfPeersReady() {
    properties.setPoolSize(4);
    assertOrder(3, self, self, null, null, 1, 2, 4);
    assertOrder(2, self, self, null, null, 1, 3, 4);
    assertOrder(1, self, self, null, null, 2, 3, 4);
    assertOrder(4, self, self, null, null, 1, 2, 3);
    assertOrder(0, self, self, null, null, 1, 2, 3, 4);
  }

  @Test
  public void testResolveOrderWeighted() {
    properties.setPoolSize(4);

    assertOrder(0, self, self, null, List.of(introducedInstance.toBuilder().weight(1234L).build()), 1, 2, 4);
    assertOrder(3, self, self, null, List.of(introducedInstance.toBuilder().weight(1232L).build()), 1, 2, 4);
    assertOrder(0, self, self, null, List.of(introducedInstance.toBuilder().weight(1234L).build()), 1, 2, 3);
    assertOrder(4, self, self, null, List.of(introducedInstance.toBuilder().weight(1232L).build()), 1, 2, 3);
    assertOrder(0, self, self, null, List.of(introducedInstance.toBuilder().weight(1234L).build()), 2, 3, 4);
    assertOrder(1, self, self, null, List.of(introducedInstance.toBuilder().weight(1232L).build()), 2, 3, 4);

    assertOrder(3, self, introducedInstance.toBuilder().weight(1234L).build(), null, null, 1, 2, 4);
    assertOrder(0, self, introducedInstance.toBuilder().weight(1232L).build(), null, null, 1, 2, 4);
    assertOrder(4, self, introducedInstance.toBuilder().weight(1234L).build(), null, null, 1, 2, 3);
    assertOrder(0, self, introducedInstance.toBuilder().weight(1232L).build(), null, null, 1, 2, 3);
    assertOrder(1, self, introducedInstance.toBuilder().weight(1234L).build(), null, null, 2, 3, 4);
    assertOrder(0, self, introducedInstance.toBuilder().weight(1232L).build(), null, null, 2, 3, 4);
  }

  private void assertOrder(
      int expected,
      @NonNull InstanceInfo self,
      @NonNull InstanceInfo candidate,
      List<InstanceInfo> discovered,
      List<InstanceInfo> introduced,
      int... readyOrderValues) {
    Map<String, InstanceInfo> peers = new HashMap<>();
    for (int readyOrderValue : readyOrderValues) {
      peers.put(
          UUID.randomUUID().toString(),
          InstanceInfo.builder()
              .id(UUID.randomUUID().toString())
              .weight((long) (Math.random() * 1000))
              .order(readyOrderValue)
              .state(STATE_ACTIVE)
              .build());
    }
    if (!self.equals(candidate)) {
      peers.put(UUID.randomUUID().toString(), candidate);
    }
    if (introduced != null) {
      introduced.forEach(peer -> peers.put(UUID.randomUUID().toString(), peer));
    }
    if (discovered != null) {
      discovered.forEach(peer -> peers.put(UUID.randomUUID().toString(), peer));
    }

    InstanceRegistry registry = new InstanceRegistry(properties, self, null);
    registry.getPeers().putAll(peers);
    assertThat(registry.resolveOrder(candidate)).isEqualTo(expected);
  }


}
