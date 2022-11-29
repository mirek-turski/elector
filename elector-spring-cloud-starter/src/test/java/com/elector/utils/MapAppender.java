package com.elector.utils;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MapAppender extends AppenderBase<ILoggingEvent> {

  @FunctionalInterface
  public interface LoggingEventMatcher {
    void matchEvent(ILoggingEvent event);
  }

  private final ConcurrentMap<Instant, ILoggingEvent> eventMap = new ConcurrentHashMap<>();
  private final List<LoggingEventMatcher> eventMatchers = new ArrayList<>();

  @Override
  protected void append(ILoggingEvent event) {
    eventMatchers.forEach(matcher -> matcher.matchEvent(event));
    eventMap.put(Instant.now(), event);
  }

  public Map<Instant, ILoggingEvent> getEventMap() {
    return eventMap;
  }

  public void registerLoggingEventMatcher(LoggingEventMatcher matcher) {
    eventMatchers.add(matcher);
  }

  public void reset() {
    eventMatchers.clear();
    eventMap.clear();
  }

}
