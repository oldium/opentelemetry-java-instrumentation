/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.logback.appender.v1_0.internal;

import static java.util.Collections.emptyList;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.internal.cache.Cache;
import io.opentelemetry.javaagent.tooling.muzzle.NoMuzzle;
import io.opentelemetry.semconv.ExceptionAttributes;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.logstash.logback.marker.LogstashMarker;
import net.logstash.logback.marker.MapEntriesAppendingMarker;
import net.logstash.logback.marker.SingleFieldAppendingMarker;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public final class LoggingEventMapper {
  // copied from CodeIncubatingAttributes
  private static final AttributeKey<String> CODE_FILEPATH = AttributeKey.stringKey("code.filepath");
  private static final AttributeKey<String> CODE_FUNCTION = AttributeKey.stringKey("code.function");
  private static final AttributeKey<Long> CODE_LINENO = AttributeKey.longKey("code.lineno");
  private static final AttributeKey<String> CODE_NAMESPACE =
      AttributeKey.stringKey("code.namespace");
  // copied from
  private static final AttributeKey<Long> THREAD_ID = AttributeKey.longKey("thread.id");
  private static final AttributeKey<String> THREAD_NAME = AttributeKey.stringKey("thread.name");

  private static final boolean supportsInstant = supportsInstant();
  private static final boolean supportsKeyValuePairs = supportsKeyValuePairs();
  private static final boolean supportsMultipleMarkers = supportsMultipleMarkers();
  private static final boolean supportsLogstashMarkers = supportsLogstashMarkers();
  private static final Cache<String, AttributeKey<String>> mdcAttributeKeys = Cache.bounded(100);
  private static final Cache<String, AttributeKey<String>> attributeKeys = Cache.bounded(100);

  private static final AttributeKey<List<String>> LOG_MARKER =
      AttributeKey.stringArrayKey("logback.marker");
  private static final AttributeKey<String> LOG_BODY_TEMPLATE =
      AttributeKey.stringKey("log.body.template");
  private static final AttributeKey<List<String>> LOG_BODY_PARAMETERS =
      AttributeKey.stringArrayKey("log.body.parameters");

  private static final Cache<Class<?>, Field> valueField = Cache.bounded(20);

  private final boolean captureExperimentalAttributes;
  private final List<String> captureMdcAttributes;
  private final boolean captureAllMdcAttributes;
  private final boolean captureCodeAttributes;
  private final boolean captureMarkerAttribute;
  private final boolean captureKeyValuePairAttributes;
  private final boolean captureLoggerContext;
  private final boolean captureArguments;
  private final boolean captureLogstashAttributes;

  private LoggingEventMapper(Builder builder) {
    this.captureExperimentalAttributes = builder.captureExperimentalAttributes;
    this.captureCodeAttributes = builder.captureCodeAttributes;
    this.captureMdcAttributes = builder.captureMdcAttributes;
    this.captureMarkerAttribute = builder.captureMarkerAttribute;
    this.captureKeyValuePairAttributes = builder.captureKeyValuePairAttributes;
    this.captureLoggerContext = builder.captureLoggerContext;
    this.captureArguments = builder.captureArguments;
    this.captureLogstashAttributes = builder.captureLogstashAttributes;
    this.captureAllMdcAttributes =
        builder.captureMdcAttributes.size() == 1 && builder.captureMdcAttributes.get(0).equals("*");
  }

  public static Builder builder() {
    return new Builder();
  }

  public void emit(LoggerProvider loggerProvider, ILoggingEvent event, long threadId) {
    String instrumentationName = event.getLoggerName();
    if (instrumentationName == null || instrumentationName.isEmpty()) {
      instrumentationName = "ROOT";
    }
    LogRecordBuilder builder =
        loggerProvider.loggerBuilder(instrumentationName).build().logRecordBuilder();
    mapLoggingEvent(builder, event, threadId);
    builder.emit();
  }

  /** Map the {@link ILoggingEvent} data model onto the {@link LogRecordBuilder}. */
  private void mapLoggingEvent(
      LogRecordBuilder builder, ILoggingEvent loggingEvent, long threadId) {
    // message
    String message = loggingEvent.getFormattedMessage();
    if (message != null) {
      builder.setBody(message);
    }

    // time
    if (supportsInstant && hasInstant(loggingEvent)) {
      setTimestampFromInstant(builder, loggingEvent);
    } else {
      long timestamp = loggingEvent.getTimeStamp();
      builder.setTimestamp(timestamp, TimeUnit.MILLISECONDS);
    }

    // level
    Level level = loggingEvent.getLevel();
    if (level != null) {
      builder.setSeverity(levelToSeverity(level));
      builder.setSeverityText(level.levelStr);
    }

    AttributesBuilder attributes = Attributes.builder();

    // throwable
    Object throwableProxy = loggingEvent.getThrowableProxy();
    Throwable throwable = null;
    if (throwableProxy instanceof ThrowableProxy) {
      // there is only one other subclass of ch.qos.logback.classic.spi.IThrowableProxy
      // and it is only used for logging exceptions over the wire
      throwable = ((ThrowableProxy) throwableProxy).getThrowable();
    }
    if (throwable != null) {
      setThrowable(attributes, throwable);
    }

    captureMdcAttributes(attributes, loggingEvent.getMDCPropertyMap());

    if (captureExperimentalAttributes) {
      attributes.put(THREAD_NAME, loggingEvent.getThreadName());
      if (threadId != -1) {
        attributes.put(THREAD_ID, threadId);
      }
    }

    if (captureCodeAttributes) {
      StackTraceElement[] callerData = loggingEvent.getCallerData();
      if (callerData != null && callerData.length > 0) {
        StackTraceElement firstStackElement = callerData[0];
        String fileName = firstStackElement.getFileName();
        if (fileName != null) {
          attributes.put(CODE_FILEPATH, fileName);
        }
        attributes.put(CODE_NAMESPACE, firstStackElement.getClassName());
        attributes.put(CODE_FUNCTION, firstStackElement.getMethodName());
        int lineNumber = firstStackElement.getLineNumber();
        if (lineNumber > 0) {
          attributes.put(CODE_LINENO, lineNumber);
        }
      }
    }

    if (captureMarkerAttribute) {
      boolean skipLogstashMarkers = supportsLogstashMarkers && captureLogstashAttributes;
      captureMarkerAttribute(attributes, loggingEvent, skipLogstashMarkers);
    }

    if (supportsKeyValuePairs && captureKeyValuePairAttributes) {
      captureKeyValuePairAttributes(attributes, loggingEvent);
    }

    if (captureLoggerContext) {
      captureLoggerContext(attributes, loggingEvent.getLoggerContextVO().getPropertyMap());
    }

    if (captureArguments
        && loggingEvent.getArgumentArray() != null
        && loggingEvent.getArgumentArray().length > 0) {
      captureArguments(attributes, loggingEvent.getMessage(), loggingEvent.getArgumentArray());
    }

    if (supportsLogstashMarkers && captureLogstashAttributes) {
      captureLogstashAttributes(attributes, loggingEvent);
    }

    builder.setAllAttributes(attributes.build());

    // span context
    builder.setContext(Context.current());
  }

  // getInstant is available since Logback 1.3
  private static boolean supportsInstant() {
    try {
      ILoggingEvent.class.getMethod("getInstant");
    } catch (NoSuchMethodException e) {
      return false;
    }

    return true;
  }

  @NoMuzzle
  private static boolean hasInstant(ILoggingEvent loggingEvent) {
    return loggingEvent.getInstant() != null;
  }

  @NoMuzzle
  private static void setTimestampFromInstant(
      LogRecordBuilder builder, ILoggingEvent loggingEvent) {
    builder.setTimestamp(loggingEvent.getInstant());
  }

  // visible for testing
  void captureMdcAttributes(AttributesBuilder attributes, Map<String, String> mdcProperties) {
    if (captureAllMdcAttributes) {
      for (Map.Entry<String, String> entry : mdcProperties.entrySet()) {
        attributes.put(getMdcAttributeKey(entry.getKey()), entry.getValue());
      }
      return;
    }

    for (String key : captureMdcAttributes) {
      String value = mdcProperties.get(key);
      if (value != null) {
        attributes.put(getMdcAttributeKey(key), value);
      }
    }
  }

  void captureArguments(AttributesBuilder attributes, String message, Object[] arguments) {
    attributes.put(LOG_BODY_TEMPLATE, message);
    attributes.put(
        LOG_BODY_PARAMETERS,
        Arrays.stream(arguments).map(String::valueOf).collect(Collectors.toList()));
  }

  public static AttributeKey<String> getMdcAttributeKey(String key) {
    return mdcAttributeKeys.computeIfAbsent(key, AttributeKey::stringKey);
  }

  private static void setThrowable(AttributesBuilder attributes, Throwable throwable) {
    // TODO (trask) extract method for recording exception into
    // io.opentelemetry:opentelemetry-api
    attributes.put(ExceptionAttributes.EXCEPTION_TYPE, throwable.getClass().getName());
    attributes.put(ExceptionAttributes.EXCEPTION_MESSAGE, throwable.getMessage());
    StringWriter writer = new StringWriter();
    throwable.printStackTrace(new PrintWriter(writer));
    attributes.put(ExceptionAttributes.EXCEPTION_STACKTRACE, writer.toString());
  }

  private static Severity levelToSeverity(Level level) {
    switch (level.levelInt) {
      case Level.ALL_INT:
      case Level.TRACE_INT:
        return Severity.TRACE;
      case Level.DEBUG_INT:
        return Severity.DEBUG;
      case Level.INFO_INT:
        return Severity.INFO;
      case Level.WARN_INT:
        return Severity.WARN;
      case Level.ERROR_INT:
        return Severity.ERROR;
      case Level.OFF_INT:
      default:
        return Severity.UNDEFINED_SEVERITY_NUMBER;
    }
  }

  @NoMuzzle
  private static void captureKeyValuePairAttributes(
      AttributesBuilder attributes, ILoggingEvent loggingEvent) {
    List<KeyValuePair> keyValuePairs = loggingEvent.getKeyValuePairs();
    if (keyValuePairs != null) {
      for (KeyValuePair keyValuePair : keyValuePairs) {
        captureKeyValueAttribute(attributes, keyValuePair.key, keyValuePair.value);
      }
    }
  }

  private static void captureKeyValueAttribute(
      AttributesBuilder attributes, Object key, Object value) {
    // empty values are not serialized
    if (key != null && value != null) {
      String keyStr = key.toString();
      // preserve type for boolean and numeric values, everything else is converted to String
      if (value instanceof Boolean) {
        attributes.put(keyStr, (Boolean) value);
      } else if (value instanceof Byte
          || value instanceof Integer
          || value instanceof Long
          || value instanceof Short) {
        attributes.put(keyStr, ((Number) value).longValue());
      } else if (value instanceof Double || value instanceof Float) {
        attributes.put(keyStr, ((Number) value).doubleValue());
      } else if (value.getClass().isArray()) {
        if (value instanceof boolean[] || value instanceof Boolean[]) {
          captureKeyArrayValueAttribute(
              attributes,
              AttributeKey.booleanArrayKey(keyStr),
              value,
              Boolean[]::new,
              o -> (Boolean) o);
        } else if (value instanceof byte[]
            || value instanceof Byte[]
            || value instanceof int[]
            || value instanceof Integer[]
            || value instanceof long[]
            || value instanceof Long[]
            || value instanceof short[]
            || value instanceof Short[]) {
          captureKeyArrayValueAttribute(
              attributes,
              AttributeKey.longArrayKey(keyStr),
              value,
              Long[]::new,
              o -> ((Number) o).longValue());
        } else if (value instanceof float[]
            || value instanceof Float[]
            || value instanceof double[]
            || value instanceof Double[]) {
          captureKeyArrayValueAttribute(
              attributes,
              AttributeKey.doubleArrayKey(keyStr),
              value,
              Double[]::new,
              o -> ((Number) o).doubleValue());
        } else {
          captureKeyArrayValueAttribute(
              attributes,
              AttributeKey.stringArrayKey(keyStr),
              value,
              String[]::new,
              String::valueOf);
        }
      } else if (value instanceof Collection) {
        captureKeyArrayValueAttribute(
            attributes,
            AttributeKey.stringArrayKey(keyStr),
            ((Collection<?>) value).toArray(),
            String[]::new,
            String::valueOf);
      } else {
        attributes.put(getAttributeKey(keyStr), String.valueOf(value));
      }
    }
  }

  private static <T> void captureKeyArrayValueAttribute(
      AttributesBuilder attributes,
      AttributeKey<List<T>> key,
      Object array,
      IntFunction<T[]> newArray,
      Function<Object, T> extractor) {
    int length = java.lang.reflect.Array.getLength(array);
    T[] typedArray = newArray.apply(length);
    int offset = 0;
    for (int i = 0; i < length; i++) {
      Object value = java.lang.reflect.Array.get(array, i);
      // empty values are not serialized
      if (value != null) {
        typedArray[i - offset] = extractor.apply(value);
      } else {
        offset++;
      }
    }
    // empty lists are not serialized
    if (length != offset) {
      attributes.put(
          key,
          Arrays.asList(offset == 0 ? typedArray : Arrays.copyOf(typedArray, length - offset)));
    }
  }

  private static void captureLoggerContext(
      AttributesBuilder attributes, Map<String, String> loggerContextProperties) {
    for (Map.Entry<String, String> entry : loggerContextProperties.entrySet()) {
      attributes.put(getAttributeKey(entry.getKey()), entry.getValue());
    }
  }

  public static AttributeKey<String> getAttributeKey(String key) {
    return attributeKeys.computeIfAbsent(key, AttributeKey::stringKey);
  }

  private static boolean supportsKeyValuePairs() {
    try {
      Class.forName("org.slf4j.event.KeyValuePair");
    } catch (ClassNotFoundException e) {
      return false;
    }
    try {
      ILoggingEvent.class.getMethod("getKeyValuePairs");
    } catch (NoSuchMethodException e) {
      return false;
    }

    return true;
  }

  private static void captureMarkerAttribute(
      AttributesBuilder attributes, ILoggingEvent loggingEvent, boolean skipLogstashMarkers) {
    if (supportsMultipleMarkers && hasMultipleMarkers(loggingEvent)) {
      captureMultipleMarkerAttributes(attributes, loggingEvent, skipLogstashMarkers);
    } else {
      captureSingleMarkerAttribute(attributes, loggingEvent, skipLogstashMarkers);
    }
  }

  @SuppressWarnings("deprecation") // getMarker is deprecate since 1.3.0
  private static void captureSingleMarkerAttribute(
      AttributesBuilder attributes, ILoggingEvent loggingEvent, boolean skipLogstashMarkers) {
    Marker marker = loggingEvent.getMarker();
    if (marker != null && (!skipLogstashMarkers || !isLogstashMarker(marker))) {
      attributes.put(LOG_MARKER, marker.getName());
    }
  }

  @NoMuzzle
  private static void captureMultipleMarkerAttributes(
      AttributesBuilder attributes, ILoggingEvent loggingEvent, boolean skipLogstashMarkers) {
    List<String> markerNames = new ArrayList<>(loggingEvent.getMarkerList().size());
    for (Marker marker : loggingEvent.getMarkerList()) {
      if (!skipLogstashMarkers || !isLogstashMarker(marker)) {
        markerNames.add(marker.getName());
      }
    }
    if (!markerNames.isEmpty()) {
      attributes.put(LOG_MARKER, markerNames.toArray(new String[0]));
    }
  }

  @NoMuzzle
  private static boolean hasMultipleMarkers(ILoggingEvent loggingEvent) {
    List<Marker> markerList = loggingEvent.getMarkerList();
    return markerList != null && markerList.size() > 1;
  }

  private static boolean supportsMultipleMarkers() {
    try {
      ILoggingEvent.class.getMethod("getMarkerList");
    } catch (NoSuchMethodException e) {
      return false;
    }

    return true;
  }

  private static void captureLogstashAttributes(
      AttributesBuilder attributes, ILoggingEvent loggingEvent) {
    try {
      if (supportsMultipleMarkers && hasMultipleMarkers(loggingEvent)) {
        captureMultipleLogstashAttributes(attributes, loggingEvent);
      } else {
        captureSingleLogstashAttribute(attributes, loggingEvent);
      }
    } catch (Throwable e) {
      // ignore
    }
  }

  @NoMuzzle
  private static boolean isLogstashMarker(Marker marker) {
    return marker instanceof LogstashMarker;
  }

  @SuppressWarnings("deprecation") // getMarker is deprecate since 1.3.0
  @NoMuzzle
  private static void captureSingleLogstashAttribute(
      AttributesBuilder attributes, ILoggingEvent loggingEvent) {
    Marker marker = loggingEvent.getMarker();
    if (isLogstashMarker(marker)) {
      LogstashMarker logstashMarker = (LogstashMarker) marker;
      captureLogstashMarker(attributes, logstashMarker);
    }
  }

  @NoMuzzle
  private static void captureMultipleLogstashAttributes(
      AttributesBuilder attributes, ILoggingEvent loggingEvent) {
    for (Marker marker : loggingEvent.getMarkerList()) {
      if (isLogstashMarker(marker)) {
        LogstashMarker logstashMarker = (LogstashMarker) marker;
        captureLogstashMarker(attributes, logstashMarker);
      }
    }
  }

  @NoMuzzle
  private static void captureLogstashMarker(
      AttributesBuilder attributes, LogstashMarker logstashMarker) {
    captureLogstashMarkerAttributes(attributes, logstashMarker);

    if (logstashMarker.hasReferences()) {
      for (Iterator<Marker> it = logstashMarker.iterator(); it.hasNext(); ) {
        Marker referenceMarker = it.next();
        if (isLogstashMarker(referenceMarker)) {
          LogstashMarker referenceLogstashMarker = (LogstashMarker) referenceMarker;
          captureLogstashMarker(attributes, referenceLogstashMarker);
        }
      }
    }
  }

  @NoMuzzle
  private static void captureLogstashMarkerAttributes(
      AttributesBuilder attributes, LogstashMarker logstashMarker) {
    if (logstashMarker instanceof SingleFieldAppendingMarker) {
      SingleFieldAppendingMarker singleFieldAppendingMarker =
          (SingleFieldAppendingMarker) logstashMarker;
      String fieldName = singleFieldAppendingMarker.getFieldName();
      Object fieldValue = extractFieldValue(singleFieldAppendingMarker);
      captureKeyValueAttribute(attributes, fieldName, fieldValue);
    } else if (logstashMarker instanceof MapEntriesAppendingMarker) {
      MapEntriesAppendingMarker mapEntriesAppendingMarker =
          (MapEntriesAppendingMarker) logstashMarker;
      Map<?, ?> map = extractMapValue(mapEntriesAppendingMarker);
      if (map != null) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          Object key = entry.getKey();
          Object value = entry.getValue();
          captureKeyValueAttribute(attributes, key, value);
        }
      }
    }
  }

  @Nullable
  private static Object extractFieldValue(SingleFieldAppendingMarker singleFieldAppendingMarker) {
    // ObjectAppendingMarker.fieldValue since v7.0
    // ObjectAppendingMarker.object since v3.0
    // RawJsonAppendingMarker.rawJson since v3.0
    Field field =
        valueField.computeIfAbsent(
            singleFieldAppendingMarker.getClass(),
            clazz -> findValueField(clazz, new String[] {"fieldValue", "object", "rawJson"}));
    if (field != null) {
      try {
        return field.get(singleFieldAppendingMarker);
      } catch (IllegalAccessException e) {
        // ignore
      }
    }
    return null;
  }

  @Nullable
  private static Map<?, ?> extractMapValue(MapEntriesAppendingMarker mapEntriesAppendingMarker) {
    // MapEntriesAppendingMarker.map since v3.0
    Field field =
        valueField.computeIfAbsent(
            mapEntriesAppendingMarker.getClass(),
            clazz -> findValueField(clazz, new String[] {"map"}));
    if (field != null) {
      try {
        Object value = field.get(mapEntriesAppendingMarker);
        if (value instanceof Map) {
          return (Map<?, ?>) value;
        }
      } catch (IllegalAccessException e) {
        // ignore
      }
    }
    return null;
  }

  @Nullable
  private static Field findValueField(Class<?> clazz, String[] fieldNames) {
    for (String fieldName : fieldNames) {
      try {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException e) {
        // ignore
      }
    }
    return null;
  }

  private static boolean supportsLogstashMarkers() {
    try {
      Class.forName("net.logstash.logback.marker.LogstashMarker");
    } catch (ClassNotFoundException e) {
      return false;
    }

    try {
      Class.forName("net.logstash.logback.marker.SingleFieldAppendingMarker");
    } catch (ClassNotFoundException e) {
      return false;
    }

    try {
      Class.forName("net.logstash.logback.marker.MapEntriesAppendingMarker");
    } catch (ClassNotFoundException e) {
      return false;
    }

    return true;
  }

  /**
   * This class is internal and is hence not for public use. Its APIs are unstable and can change at
   * any time.
   */
  public static final class Builder {
    private boolean captureExperimentalAttributes;
    private List<String> captureMdcAttributes = emptyList();
    private boolean captureCodeAttributes;
    private boolean captureMarkerAttribute;
    private boolean captureKeyValuePairAttributes;
    private boolean captureLoggerContext;
    private boolean captureArguments;
    private boolean captureLogstashAttributes;

    Builder() {}

    @CanIgnoreReturnValue
    public Builder setCaptureExperimentalAttributes(boolean captureExperimentalAttributes) {
      this.captureExperimentalAttributes = captureExperimentalAttributes;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCaptureMdcAttributes(List<String> captureMdcAttributes) {
      this.captureMdcAttributes = captureMdcAttributes;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCaptureCodeAttributes(boolean captureCodeAttributes) {
      this.captureCodeAttributes = captureCodeAttributes;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCaptureMarkerAttribute(boolean captureMarkerAttribute) {
      this.captureMarkerAttribute = captureMarkerAttribute;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCaptureKeyValuePairAttributes(boolean captureKeyValuePairAttributes) {
      this.captureKeyValuePairAttributes = captureKeyValuePairAttributes;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCaptureLoggerContext(boolean captureLoggerContext) {
      this.captureLoggerContext = captureLoggerContext;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCaptureArguments(boolean captureArguments) {
      this.captureArguments = captureArguments;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCaptureLogstashAttributes(boolean captureLogstashAttributes) {
      this.captureLogstashAttributes = captureLogstashAttributes;
      return this;
    }

    public LoggingEventMapper build() {
      return new LoggingEventMapper(this);
    }
  }
}
