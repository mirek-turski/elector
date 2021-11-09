package com.selfrule;

import org.mapstruct.Mapper;

/** Maps between {@link InstanceInfo} and {@link InstanceEvent} objects */
@Mapper
public interface InstanceMapper {
  /**
   * Maps {@link InstanceEvent} to {@link InstanceInfo}
   *
   * @param event source object
   * @return mapped object
   */
  InstanceInfo eventToInfo(InstanceEvent event);

  /**
   * Maps {@link InstanceInfo} to {@link InstanceEvent}
   *
   * @param info source object
   * @return mapped object
   */
  InstanceEvent infoToEvent(InstanceInfo info);
}
