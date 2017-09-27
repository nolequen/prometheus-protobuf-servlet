package io.prometheus.client.exporter;

import io.prometheus.client.Collector;
import io.prometheus.client.Metrics;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ProtobufFormatter {
  public static final @NotNull String CONTENT_TYPE = "application/vnd.google.protobuf; proto=io.prometheus.client.MetricFamily; encoding=delimited";

  private final @NotNull Enumeration<Collector.MetricFamilySamples> metrics;

  public ProtobufFormatter(@NotNull Enumeration<Collector.MetricFamilySamples> metrics) {
    this.metrics = metrics;
  }

  public void write(@NotNull OutputStream stream) throws IOException {
    for (Collector.MetricFamilySamples family : Collections.list(metrics)) {
      Collectors.consume(stream, family);
    }
  }

  private enum Collectors {
    Counter(Collector.Type.COUNTER, CounterConsumer::new),
    Gauge(Collector.Type.GAUGE, GaugeConsumer::new);

    private static final @NotNull Iterable<Collectors> collectors = Arrays.asList(values());

    private final @NotNull Collector.Type type;
    private final @NotNull ConsumerSupplier supplier;

    public static void consume(@NotNull OutputStream stream, @NotNull Collector.MetricFamilySamples family) throws IOException {
      for (Collectors collector : collectors) {
        if (collector.type == family.type) {
          collector.consumer(stream, family.name, family.help).consume(family.samples);
          return;
        }
      }
    }

    private Collectors(@NotNull Collector.Type type, @NotNull ConsumerSupplier supplier) {
      this.type = type;
      this.supplier = supplier;
    }

    private @NotNull MetricsConsumer consumer(@NotNull OutputStream stream, @NotNull String name, @NotNull String help) {
      return supplier.get(stream, name, help);
    }

    private interface ConsumerSupplier extends Serializable {

      @NotNull MetricsConsumer get(@NotNull OutputStream stream, @NotNull String name, @NotNull String help);
    }
  }

  private abstract static class MetricsConsumer {
    private final @NotNull OutputStream stream;
    private final @NotNull Metrics.MetricFamily.Builder family;
    private final @NotNull Metrics.Metric.Builder metrics;

    protected MetricsConsumer(@NotNull OutputStream stream, @NotNull Metrics.MetricType type, @NotNull String name, @NotNull String help) {
      this.stream = stream;
      family = Metrics.MetricFamily.newBuilder().setName(name).setHelp(help).setType(type);
      metrics = Metrics.Metric.newBuilder();
    }

    public final void consume(@NotNull Iterable<Collector.MetricFamilySamples.Sample> samples) throws IOException {
      for (Collector.MetricFamilySamples.Sample sample : samples) {
        addMetric(() -> consume(sample).apply(metrics), sample.labelNames, sample.labelValues);
      }
      family.build().writeDelimitedTo(stream);
    }

    protected abstract @NotNull Function<Metrics.Metric.Builder, Metrics.Metric> consume(@NotNull Collector.MetricFamilySamples.Sample sample);

    private void addMetric(@NotNull Supplier<Metrics.Metric> supplier, @NotNull List<String> labelNames, @NotNull List<String> labelValues) {
      final int previousCount = addLabels(labelNames, labelValues);
      final Metrics.Metric metric = supplier.get();
      for (int i = labelValues.size() - 1; i >= 0; i--) {
        metrics.removeLabel(previousCount + i);
      }
      family.addMetric(metric);
    }

    private int addLabels(@NotNull List<String> labelNames, @NotNull List<String> labelValues) {
      final int labelsCount = metrics.getLabelCount();
      for (int i = 0; i < labelValues.size(); i++) {
        final String name = labelNames.get(i);
        final String value = labelValues.get(i);
        metrics.addLabel(Metrics.LabelPair.newBuilder().setName(name).setValue(value).build());
      }
      return labelsCount;
    }
  }

  private static final class CounterConsumer extends MetricsConsumer {

    public CounterConsumer(@NotNull OutputStream stream, @NotNull String name, @NotNull String help) {
      super(stream, Metrics.MetricType.COUNTER, name, help);
    }

    @Override
    protected @NotNull Function<Metrics.Metric.Builder, Metrics.Metric> consume(@NotNull Collector.MetricFamilySamples.Sample sample) {
      return metrics -> metrics.setCounter(Metrics.Counter.newBuilder().setValue(sample.value).build()).build();
    }
  }

  private static final class GaugeConsumer extends MetricsConsumer {

    public GaugeConsumer(@NotNull OutputStream stream, @NotNull String name, @NotNull String help) {
      super(stream, Metrics.MetricType.GAUGE, name, help);
    }

    @Override
    protected @NotNull Function<Metrics.Metric.Builder, Metrics.Metric> consume(@NotNull Collector.MetricFamilySamples.Sample sample) {
      return metrics -> metrics.setGauge(Metrics.Gauge.newBuilder().setValue(sample.value).build()).build();
    }
  }
}
