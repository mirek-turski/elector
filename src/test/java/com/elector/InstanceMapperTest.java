package com.elector;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InstanceMapperTest {

  private final InstanceMapper mapper = Mappers.getMapper(InstanceMapper.class);

  private final InstanceEvent event =
      InstanceEvent.builder()
          .id("id")
          .name("name")
          .ip("ip")
          .namespace("namespace")
          .state("state")
          .order(12)
          .weight(13L)
          .build();

  private final InstanceInfo info =
      InstanceInfo.builder()
          .id("id")
          .name("name")
          .ip("ip")
          .namespace("namespace")
          .state("state")
          .order(12)
          .weight(13L)
          .build();

  @Test
  public void testMessageToInfo() {
    assertEquals(info, mapper.eventToInfo(event));
  }

  @Test
  public void testInfoToMessage() {
    assertEquals(event, mapper.infoToEvent(info));
  }
}
