package io.prometheus.client.exporter;

import io.prometheus.client.Collector;
import io.prometheus.client.Metrics;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.*;
import java.util.function.BiConsumer;
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

  private enum DoubleConverter {
    PositiveInfinity("+Inf", Double.POSITIVE_INFINITY),
    NegativeInfinity("-Inf", Double.NEGATIVE_INFINITY),
    NaN("NaN", Double.NaN);

    private static final @NotNull Collection<DoubleConverter> values = Arrays.asList(values());

    private final @NotNull String source;
    private final double result;

    public static double convert(@NotNull String value) {
      return values.stream()
          .filter(converter -> converter.source.equals(value))
          .findFirst()
          .map(converter -> converter.result)
          .orElseGet(() -> Double.parseDouble(value));
    }

    private DoubleConverter(@NotNull String source, double result) {
      this.source = source;
      this.result = result;
    }
  }

  private enum Collectors {
    Counter(Collector.Type.COUNTER, CounterConsumer::new),
    Gauge(Collector.Type.GAUGE, GaugeConsumer::new),
    Summary(Collector.Type.SUMMARY, SummaryConsumer::new),
    Histogram(Collector.Type.HISTOGRAM, HistogramConsumer::new);

    private static final @NotNull Collection<Collectors> collectors = Arrays.asList(values());

    private final @NotNull Collector.Type type;
    private final @NotNull ConsumerProvider supplier;

    public static void consume(@NotNull OutputStream stream, @NotNull Collector.MetricFamilySamples family) throws IOException {
      for (Collectors collector : collectors) {
        if (collector.type == family.type) {
          collector.consumer(stream, family.name, family.help).write(family);
          return;
        }
      }
    }

    private Collectors(@NotNull Collector.Type type, @NotNull ConsumerProvider supplier) {
      this.type = type;
      this.supplier = supplier;
    }

    private @NotNull MetricsConsumer consumer(@NotNull OutputStream stream, @NotNull String name, @NotNull String help) {
      return supplier.get(stream, name, help);
    }

    private interface ConsumerProvider extends Serializable {

      @NotNull MetricsConsumer get(@NotNull OutputStream stream, @NotNull String name, @NotNull String help);
    }
  }

  private interface Builder {

    void consumeSampleCount(long value);

    void consumeSampleSum(double value);

    void consumeParticle(double particle, double sample);

    @NotNull Metrics.Metric build(@NotNull Metrics.Metric.Builder metrics);
  }

  private abstract static class MetricsConsumer {
    private final @NotNull OutputStream stream;
    private final @NotNull Metrics.MetricFamily.Builder family;
    private final @NotNull Metrics.Metric.Builder metrics = Metrics.Metric.newBuilder();

    protected MetricsConsumer(@NotNull OutputStream stream, @NotNull Metrics.MetricType type, @NotNull String name, @NotNull String help) {
      this.stream = stream;
      family = Metrics.MetricFamily.newBuilder().setName(name).setHelp(help).setType(type);
    }

    public final void write(@NotNull Collector.MetricFamilySamples samples) throws IOException {
      consume(samples.samples);
      family.build().writeDelimitedTo(stream);
    }

    protected abstract void consume(@NotNull Iterable<Collector.MetricFamilySamples.Sample> samples);

    protected final void apply(@NotNull Function<Metrics.Metric.Builder, Metrics.Metric> supplier, @NotNull List<String> labelNames, @NotNull List<String> labelValues) {
      final int labelsCount = metrics.getLabelCount();
      for (int i = 0; i < labelValues.size(); i++) {
        metrics.addLabel(
            Metrics.LabelPair.newBuilder()
                .setName(labelNames.get(i))
                .setValue(labelValues.get(i))
                .build()
        );
      }
      final Metrics.Metric metric = supplier.apply(metrics);
      for (int i = labelValues.size() - 1; i >= 0; i--) {
        metrics.removeLabel(labelsCount + i);
      }
      family.addMetric(metric);
    }
  }

  private static final class CounterConsumer extends MetricsConsumer {

    public CounterConsumer(@NotNull OutputStream stream, @NotNull String name, @NotNull String help) {
      super(stream, Metrics.MetricType.COUNTER, name, help);
    }

    @Override
    protected void consume(@NotNull Iterable<Collector.MetricFamilySamples.Sample> samples) {
      for (Collector.MetricFamilySamples.Sample sample : samples) {
        apply(metrics -> metrics.setCounter(Metrics.Counter.newBuilder().setValue(sample.value).build()).build(), sample.labelNames, sample.labelValues);
      }
    }
  }

  private static final class GaugeConsumer extends MetricsConsumer {

    public GaugeConsumer(@NotNull OutputStream stream, @NotNull String name, @NotNull String help) {
      super(stream, Metrics.MetricType.GAUGE, name, help);
    }

    @Override
    protected void consume(@NotNull Iterable<Collector.MetricFamilySamples.Sample> samples) {
      for (Collector.MetricFamilySamples.Sample sample : samples) {
        apply(metrics -> metrics.setGauge(Metrics.Gauge.newBuilder().setValue(sample.value).build()).build(), sample.labelNames, sample.labelValues);
      }
    }
  }

  private static final class Builders<T> {
    private final @NotNull Map<List<String>, T> labels = new HashMap<>();
    private final @NotNull Supplier<T> creator;

    private final @NotNull String particle;

    public Builders(@NotNull Supplier<T> creator, @NotNull String particle) {
      this.creator = creator;
      this.particle = particle;
    }

    public @NotNull String particle() {
      return particle;
    }

    public @NotNull T get(@NotNull List<String> key) {
      return labels.computeIfAbsent(key, l -> creator.get());
    }

    public void forEach(@NotNull BiConsumer<List<String>, T> action) {
      labels.forEach(action);
    }
  }

  private abstract static class GenericMetricsConsumer<T extends Builder> extends MetricsConsumer {

    protected GenericMetricsConsumer(@NotNull OutputStream stream, @NotNull Metrics.MetricType type, @NotNull String name, @NotNull String help) {
      super(stream, type, name, help);
    }

    @Override
    protected final void consume(@NotNull Iterable<Collector.MetricFamilySamples.Sample> samples) {
      final Builders<T> builders = createLabels();

      final Map<List<String>, List<String>> lables = new HashMap<>();
      for (Collector.MetricFamilySamples.Sample sample : samples) {
        if (sample.name.endsWith("_count")) {
          builders.get(sample.labelNames).consumeSampleCount((long) sample.value);
          continue;
        }
        if (sample.name.endsWith("_sum")) {
          builders.get(sample.labelNames).consumeSampleSum(sample.value);
          continue;
        } // if (sample.name.endsWith("_bucket")) for Histogram
        final int particle = sample.labelNames.indexOf(builders.particle());
        if (particle == -1) {
          continue;
        }
        final List<String> labelNames = new ArrayList<>(sample.labelNames);
        labelNames.remove(particle);
        final List<String> lableValues = new ArrayList<>(sample.labelValues);
        lableValues.remove(particle);
        lables.put(labelNames, lableValues);
        builders.get(labelNames).consumeParticle(DoubleConverter.convert(sample.labelValues.get(particle)), sample.value);
      }
      builders.forEach((k, v) -> apply(v::build, k, lables.get(k)));
    }

    protected abstract @NotNull ProtobufFormatter.Builders<T> createLabels();
  }

  private static final class SummaryConsumer extends GenericMetricsConsumer<SummaryConsumer.SummaryBuilder> {

    public SummaryConsumer(@NotNull OutputStream stream, @NotNull String name, @NotNull String help) {
      super(stream, Metrics.MetricType.SUMMARY, name, help);
    }

    @Override
    protected @NotNull ProtobufFormatter.Builders<SummaryBuilder> createLabels() {
      return new Builders<>(SummaryBuilder::new, "quantile");
    }

    static final class SummaryBuilder implements Builder {
      private final @NotNull Metrics.Summary.Builder builder = Metrics.Summary.newBuilder();

      @Override
      public void consumeSampleCount(long value) {
        builder.setSampleCount(value);
      }

      @Override
      public void consumeSampleSum(double value) {
        builder.setSampleSum(value);
      }

      @Override
      public void consumeParticle(double particle, double sample) {
        builder.addQuantile(Metrics.Quantile.newBuilder().setQuantile(particle).setValue(sample));
      }

      @Override
      public @NotNull Metrics.Metric build(@NotNull Metrics.Metric.Builder metrics) {
        return metrics.setSummary(builder.build()).build();
      }
    }
  }

  private static final class HistogramConsumer extends GenericMetricsConsumer<HistogramConsumer.HistogramBuilder> {

    public HistogramConsumer(@NotNull OutputStream stream, @NotNull String name, @NotNull String help) {
      super(stream, Metrics.MetricType.HISTOGRAM, name, help);
    }

    @Override
    protected @NotNull ProtobufFormatter.Builders<HistogramBuilder> createLabels() {
      return new Builders<>(HistogramBuilder::new, "le");
    }

    static final class HistogramBuilder implements Builder {
      private final @NotNull Metrics.Histogram.Builder builder = Metrics.Histogram.newBuilder();

      @Override
      public void consumeSampleCount(long value) {
        builder.setSampleCount(value);
      }

      @Override
      public void consumeSampleSum(double value) {
        builder.setSampleSum(value);
      }

      @Override
      public void consumeParticle(double particle, double sample) {
        builder.addBucket(Metrics.Bucket.newBuilder().setCumulativeCount((long) sample).setUpperBound(particle));
      }

      @Override
      public @NotNull Metrics.Metric build(@NotNull Metrics.Metric.Builder metrics) {
        return metrics.setHistogram(builder.build()).build();
      }
    }
  }
}
