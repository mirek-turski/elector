package com.elector.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import org.slf4j.LoggerFactory;

public class LogUtils {

  public static Logger createConsoleLogger(String name, Level level) {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    context.getLogger("ROOT").setLevel(Level.ERROR);

    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setPattern("%date{HH:mm:ss.SSS} %5.5level{0} [%8.8thread] %logger{10} %msg%n");
    encoder.setContext(context);
    encoder.start();

    ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
    consoleAppender.setEncoder(encoder);
    consoleAppender.setContext(context);
    consoleAppender.start();

    Logger logger = (Logger) LoggerFactory.getLogger(name);
    logger.addAppender(consoleAppender);
    logger.setLevel(level);
    logger.setAdditive(false); /* set to true if root should log too */

    return logger;
  }

}
