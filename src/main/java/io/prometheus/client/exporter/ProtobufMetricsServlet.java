package io.prometheus.client.exporter;

import io.prometheus.client.CollectorRegistry;
import org.jetbrains.annotations.NotNull;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ProtobufMetricsServlet extends HttpServlet {
  private final @NotNull CollectorRegistry registry;

  public ProtobufMetricsServlet() {
    this(CollectorRegistry.defaultRegistry);
  }

  public ProtobufMetricsServlet(@NotNull CollectorRegistry registry) {
    this.registry = registry;
  }

  @Override
  protected void doGet(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(ProtobufFormatter.CONTENT_TYPE);
    try (final OutputStream output = response.getOutputStream()) {
      new ProtobufFormatter(registry.filteredMetricFamilySamples(names(request))).write(output);
      output.flush();
    }
  }

  @Override
  protected void doPost(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) throws IOException {
    doGet(request, response);
  }

  private static @NotNull Set<String> names(@NotNull HttpServletRequest request) {
    final String[] names = request.getParameterValues("name[]");
    return names == null ? Collections.emptySet() : new HashSet<>(Arrays.asList(names));
  }
}
