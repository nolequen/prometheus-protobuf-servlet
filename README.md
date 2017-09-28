# prometheus-protobuf-servlet

[![Build Status](https://img.shields.io/travis/nolequen/prometheus-protobuf-servlet.svg?branch=master)](https://travis-ci.org/nolequen/prometheus-protobuf-servlet)
[![Dependency Status](https://www.versioneye.com/user/projects/59cb9c0b6725bd11fffde5d3/badge.svg)](https://www.versioneye.com/user/projects/59cb9c0b6725bd11fffde5d3)
[![Codebeat](https://codebeat.co/badges/9086adaa-3c9d-4c3a-81cc-9bcf1a4bddc0)](https://codebeat.co/projects/github-com-nolequen-prometheus-protobuf-servlet-master)

[Protobuf](https://prometheus.io/docs/instrumenting/exposition_formats/) exposition format support for [Prometheus](https://prometheus.io/) client.

Currently only [Gauge](https://prometheus.io/docs/concepts/metric_types/#gauge) and [Counter](https://prometheus.io/docs/concepts/metric_types/#counter) collectors are supported.

### Usage

The simple way to expose the metrics used in your code using Protobuf format is to add `ProtobufMetricsServlet` to your HTTP server.
For example, you may do it with [Jetty](https://www.eclipse.org/jetty/) server:

```java
final Server server = new Server(8080);
final ServletContextHandler context = new ServletContextHandler();
context.setContextPath("/");
server.setHandler(context);

context.addServlet(new ServletHolder(new ProtobufMetricsServlet()), "/metrics");
```

It also supports time series restriction using `?name[]=` URL parameter.

Furthermore it is possible to use `ProtobufFormatter` directly and expose the result in any other way.