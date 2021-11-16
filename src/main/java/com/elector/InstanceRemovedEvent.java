package com.elector;

import org.springframework.context.ApplicationEvent;

/** Event sent when an instance of the service was removed. */
public class InstanceRemovedEvent extends ApplicationEvent {

  private final InstanceInfo instanceInfo;

  /**
   * Create a new ApplicationEvent.
   *
   * @param source the object on which the event initially occurred (never {@code null})
   * @param info contains data od the removed instance
   */
  public InstanceRemovedEvent(Object source, InstanceInfo info) {
    super(source);
    this.instanceInfo = info;
  }

  public InstanceInfo getInstanceInfo() {
    return this.instanceInfo;
  }

  public String toString() {
    return "InstanceRemovedEvent(instanceInfo=" + this.getInstanceInfo() + ")";
  }
}
