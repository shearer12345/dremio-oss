/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.dac.server;

import java.net.InetAddress;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.util.EnumSet;

import javax.inject.Provider;
import javax.servlet.DispatcherType;
import javax.ws.rs.container.DynamicFeature;

import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import com.dremio.dac.daemon.DremioBinder;
import com.dremio.dac.server.socket.SocketServlet;
import com.dremio.options.OptionManager;
import com.dremio.service.SingletonRegistry;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.tokens.TokenManager;
import com.dremio.telemetry.api.tracing.http.ServerTracingFilter;
import com.google.common.annotations.VisibleForTesting;

/**
 * Provides Dremio web server
 */
public class DremioServer {

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DremioServer.class);

  private final Server embeddedJetty;

  private KeyStore trustStore;
  private AccessLogFilter accessLogFilter;
  private int port = -1;
  private ServerConnector serverConnector;
  private ServletContextHandler servletContextHandler;
  private boolean serviceStarted = false;

  public DremioServer() {
    embeddedJetty = new Server();
  }

  public AccessLogFilter getAccessLogFilter() {
    return accessLogFilter;
  }

  public void startDremioServer(
    SingletonRegistry registry,
    DACConfig config,
    Provider<RestServerV2> restServerProvider,
    Provider<APIServer> apiServerProvider,
    Provider<ScimServer> scimServerProvider,
    DremioBinder dremioBinder,
    String uiType,
    boolean isInternalUS
  ) throws Exception {
    try {
      if (!embeddedJetty.isRunning()) {
        createConnector(config);
        addHandlers();
      }

      // security header filters
      SecurityHeadersFilter securityHeadersFilter = new SecurityHeadersFilter(registry.provider(OptionManager.class));
      servletContextHandler.addFilter(new FilterHolder(securityHeadersFilter), "/*", EnumSet.of(DispatcherType.REQUEST));

      // Generic Response Headers filter for api responses
      servletContextHandler.addFilter(GenericResponseHeadersFilter.class.getName(), "/apiv2/*", EnumSet.of(DispatcherType.REQUEST));
      servletContextHandler.addFilter(GenericResponseHeadersFilter.class.getName(), "/api/*", EnumSet.of(DispatcherType.REQUEST));

      // add the font mime type.
      final MimeTypes mimeTypes = servletContextHandler.getMimeTypes();
      mimeTypes.addMimeMapping("woff2", "application/font-woff2; charset=utf-8");
      servletContextHandler.setMimeTypes(mimeTypes);

      // WebSocket API
      final SocketServlet servlet = new SocketServlet(registry.lookup(JobsService.class), registry.lookup(TokenManager.class));
      final ServletHolder wsHolder = new ServletHolder(servlet);
      wsHolder.setInitOrder(3);
      servletContextHandler.addServlet(wsHolder, "/apiv2/socket");

      // Rest API
      ResourceConfig restServer = restServerProvider.get();

      restServer.property(RestServerV2.ERROR_STACKTRACE_ENABLE, config.sendStackTraceToClient);
      restServer.property(RestServerV2.TEST_API_ENABLE, config.allowTestApis);
      restServer.property(RestServerV2.FIRST_TIME_API_ENABLE, isInternalUS);

      restServer.register(dremioBinder);
      restServer.register((DynamicFeature) (resourceInfo, context) -> context.register(TracingFilter.class));

      final ServletHolder restHolder = new ServletHolder(new ServletContainer(restServer));
      restHolder.setInitOrder(2);
      servletContextHandler.addServlet(restHolder, "/apiv2/*");

      // Public API
      ResourceConfig apiServer = apiServerProvider.get();
      apiServer.register(dremioBinder);
      apiServer.register((DynamicFeature) (resourceInfo, context) -> context.register(TracingFilter.class));

      final ServletHolder apiHolder = new ServletHolder(new ServletContainer(apiServer));
      apiHolder.setInitOrder(3);
      servletContextHandler.addServlet(apiHolder, "/api/v3/*");

      // SCIM API
      ResourceConfig scimServer = scimServerProvider.get();
      scimServer.register(dremioBinder);
      scimServer.register((DynamicFeature) (resourceInfo, context) -> context.register(TracingFilter.class));

      final ServletHolder scimHolder = new ServletHolder(new ServletContainer(scimServer));
      scimHolder.setInitOrder(4);
      servletContextHandler.addServlet(scimHolder, "/scim/v2/*");

      if (config.verboseAccessLog) {
        accessLogFilter = new AccessLogFilter();
        servletContextHandler.addFilter(
          new FilterHolder(accessLogFilter),
          "/*",
          EnumSet.of(DispatcherType.REQUEST));
      }

      if (config.serveUI) {
        final String basePath = "rest/dremio_static/";
        final String markerPath = String.format("META-INF/%s.properties", uiType);

        final ServletHolder fallbackServletHolder = new ServletHolder("fallback-servlet", registry.lookup(DremioServlet.class));
        addStaticPath(fallbackServletHolder, basePath, markerPath);
        servletContextHandler.addServlet(fallbackServletHolder, "/*");
      }

      if (!embeddedJetty.isRunning()) {
        embeddedJetty.start();
      }

      setPortFromConnector();
      logger.info("Started on {}://localhost:" + port, config.webSSLEnabled() ? "https" : "http");

      serviceStarted = true;
    } catch (Exception ex) {
      throw new ServerErrorException(ex);
    }
  }

  protected void createConnector(DACConfig config) throws Exception {
    if (config.webSSLEnabled()) {
      Pair<ServerConnector, KeyStore> connectorTrustStorePair =
        new HttpsConnectorGenerator().createHttpsConnector(embeddedJetty, config.getConfig(),
          config.thisNode, InetAddress.getLocalHost().getCanonicalHostName());
      serverConnector = connectorTrustStorePair.getLeft();
      trustStore = connectorTrustStorePair.getRight();
    } else {
      serverConnector = new ServerConnector(embeddedJetty, new HttpConnectionFactory(new HttpConfiguration()));
    }
    if (!config.autoPort) {
      serverConnector.setPort(config.getHttpPort());
    }

    embeddedJetty.addConnector(serverConnector);
  }

  protected void addHandlers() {
    // root handler with request logging
    final RequestLogHandler rootHandler = new RequestLogHandler();
    embeddedJetty.insertHandler(rootHandler);
    RequestLogImpl_Jetty_Fix requestLogger = new RequestLogImpl_Jetty_Fix();
    requestLogger.setResource("/logback-access.xml");
    rootHandler.setRequestLog(requestLogger);

    // gzip handler.
    final GzipHandler gzipHandler = new GzipHandler();
    // gzip handler interferes with ChunkedOutput, so exclude the job download path
    gzipHandler.addExcludedPaths("/apiv2/job/*", "/api/v3/support-bundle/*");
    rootHandler.setHandler(gzipHandler);

    // servlet handler for everything (to manage path mapping)
    servletContextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
    servletContextHandler.setContextPath("/");
    gzipHandler.setHandler(servletContextHandler);

    // error handler
    final ErrorHandler errorHandler = new ErrorHandler();
    errorHandler.setShowStacks(true);
    errorHandler.setShowMessageInTitle(true);
    embeddedJetty.setErrorHandler(errorHandler);
  }

  protected void addStaticPath(ServletHolder holder, String basePath, String relativeMarkerPathToResource) throws URISyntaxException {
    String path = Resource.newClassPathResource(relativeMarkerPathToResource).getURL().toString();
    final String fullBasePath = path.substring(0, path.length() - relativeMarkerPathToResource.length()) + basePath;
    holder.setInitParameter("dirAllowed", "false");
    holder.setInitParameter("pathInfoOnly", "true");
    holder.setInitParameter("resourceBase", fullBasePath);
  }

  public int getPort() {
    return port;
  }

  protected void setPortFromConnector() {
    port = serverConnector.getLocalPort();
  }

  protected ServletContextHandler getServletContextHandler() {
    return servletContextHandler;
  }

  protected boolean isServiceStarted() {
    return serviceStarted;
  }

  /**
   * Get the trust store containing the self-signed certificate (not the private key).
   *
   * @return trust store, null if the cert is not generated by the server
   */
  @VisibleForTesting
  KeyStore getTrustStore() {
    return trustStore;
  }

  public Server getJettyServer() {
    return embeddedJetty;
  }

  public void close() throws Exception {
    embeddedJetty.stop();
  }

  /**
   * Wrapper extension to tracing filter to enable registration of class instead of instance.
   * Registering an object prevents context injection. Hence registering a class.
   */
  public static class TracingFilter extends ServerTracingFilter {
    public TracingFilter() {
      super(true, "x-tracing-enabled");
    }
  }
}
