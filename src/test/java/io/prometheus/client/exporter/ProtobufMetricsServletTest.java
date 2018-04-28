package io.prometheus.client.exporter;

import io.prometheus.client.*;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.*;

@RunWith(Parameterized.class)
public final class ProtobufMetricsServletTest {

  @Parameterized.Parameters
  public static @NotNull Collection<CollectorRegistry> registries() {
    return Arrays.asList(new CollectorRegistry(), CollectorRegistry.defaultRegistry);
  }

  private static final @NotNull String PATH = "/metrics";
  private static final @NotNull String HOST = "localhost";
  private static final int PORT = 8080;

  private static final @NotNull String[] LABEL_NAMES = {"test_label_1", "test_label_2"};
  private static final @NotNull String[] LABEL_VALUES = {"test_label_value_1", "test_label_value_2"};

  private @Nullable Server server;

  @Parameterized.Parameter
  public @NotNull CollectorRegistry registry;

  @Before
  public void setUp() throws Exception {
    server = new Server(new InetSocketAddress(HOST, PORT));

    final ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");
    server.setHandler(context);

    context.addServlet(new ServletHolder(new ProtobufMetricsServlet(registry)), PATH);

    server.start();
  }

  @After
  public void tearDown() throws Exception {
    if (server != null) {
      server.stop();
    }
    registry.clear();
  }

  @Test
  public void counter() throws Exception {
    verify(
        Counter::build,
        Counter.Child::inc,
        Counter.Child::get,
        metric -> metric.getCounter().getValue()
    );
  }

  @Test
  public void gauge() throws Exception {
    verify(
        Gauge::build,
        gauge -> gauge.set(random()),
        Gauge.Child::get,
        metric -> metric.getGauge().getValue()
    );
  }

  @Test
  public void summaryCount() throws Exception {
    verify(
        ProtobufMetricsServletTest::summary,
        summary -> summary.observe(random()),
        metric -> metric.get().count,
        metric -> metric.getSummary().getSampleCount()
    );
  }

  @Test
  public void summarySum() throws Exception {
    verify(
        ProtobufMetricsServletTest::summary,
        summary -> summary.observe(random()),
        metric -> metric.get().sum,
        metric -> metric.getSummary().getSampleSum()
    );
  }

  @Test
  public void histogramBuckets() throws Exception {
    verify(
        ProtobufMetricsServletTest::histogram,
        histogram -> histogram.observe(random()),
        metric -> metric.get().buckets.length,
        metric -> metric.getHistogram().getBucketCount()
    );
  }

  @Test
  public void histogramSum() throws Exception {
    verify(
        ProtobufMetricsServletTest::histogram,
        histogram -> histogram.observe(random()),
        metric -> metric.get().sum,
        metric -> metric.getHistogram().getSampleSum()
    );
  }

  @Test
  public void all() throws Exception {
    metric(Counter::build, Counter.Child::inc).apply("test_counter");
    metric(Gauge::build, gauge -> gauge.set(random())).apply("test_gauge");
    metric(ProtobufMetricsServletTest::summary, summary -> summary.observe(random())).apply("test_summary");
    metric(ProtobufMetricsServletTest::histogram, histogram -> histogram.observe(random())).apply("test_histogram");
    verify(family -> { /* do nothing */ });
  }

  private static double random() {
    return ThreadLocalRandom.current().nextDouble();
  }

  private static @NotNull Summary.Builder summary(@NotNull String name, @NotNull String help) {
    return Summary.build(name, help).quantile(random(), random());
  }

  private static @NotNull Histogram.Builder histogram(@NotNull String name, @NotNull String help) {
    return Histogram.build(name, help).buckets(random());
  }

  private <C, T extends SimpleCollector<C>> void verify(
      @NotNull BiFunction<String, String, SimpleCollector.Builder<?, T>> builder,
      @NotNull Consumer<C> action,
      @NotNull ToDoubleFunction<C> expectation,
      @NotNull ToDoubleFunction<Metrics.Metric> actual) throws Exception {

    final String name = "test_metric_name";

    final C instance = metric(builder, action).apply(name);

    verify(family -> {
      Assert.assertEquals(name, family.getName());
      Assert.assertEquals(name, family.getHelp());

      final Metrics.Metric metric = family.getMetric(0);

      final List<Metrics.LabelPair> labels = metric.getLabelList();
      Assert.assertEquals(LABEL_NAMES.length, labels.size());

      final BiConsumer<Function<Metrics.LabelPair, String>, String[]> matcher =
          (getter, values) -> labels.stream().map(getter).forEach(label -> Assert.assertTrue(Arrays.stream(values).anyMatch(label::equals)));

      matcher.accept(Metrics.LabelPair::getName, LABEL_NAMES);
      matcher.accept(Metrics.LabelPair::getValue, LABEL_VALUES);

      final double expected = expectation.applyAsDouble(instance);
      Assert.assertEquals(expected, actual.applyAsDouble(metric), Math.ulp(expected));
    });
  }

  private @NotNull <C, T extends SimpleCollector<C>> Function<String, C> metric(
      @NotNull BiFunction<String, String, SimpleCollector.Builder<?, T>> builder,
      @NotNull Consumer<C> action) {
    return name -> {
      final C metric = metric(collector(builder.apply(name, name)));
      action.accept(metric);
      return metric;
    };
  }

  private @NotNull <T extends SimpleCollector<?>> T collector(@NotNull SimpleCollector.Builder<?, T> builder) {
    return builder.labelNames(LABEL_NAMES).create().register(registry);
  }

  private static @NotNull <T> T metric(@NotNull SimpleCollector<T> collector) {
    return collector.labels(LABEL_VALUES);
  }

  private static void verify(@NotNull Consumer<Metrics.MetricFamily> assertion) throws Exception {
    final HttpClient client = new HttpClient();
    try {
      client.start();

      final ContentResponse response = client.GET("http://" + HOST + ':' + PORT + PATH);
      Assert.assertEquals(response.getStatus(), HttpStatus.OK_200);

      assertion.accept(Metrics.MetricFamily.parseDelimitedFrom(new ByteArrayInputStream(response.getContent())));
    } finally {
      client.stop();
    }
  }
}
