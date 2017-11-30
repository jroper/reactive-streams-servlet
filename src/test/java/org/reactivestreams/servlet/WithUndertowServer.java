package org.reactivestreams.servlet;

import io.undertow.Undertow;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.util.ImmediateInstanceHandle;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

public interface WithUndertowServer extends WithVerificationServer {
  @Override
  default VerificationServer createServer() {

    class UndertowServer implements VerificationServer {
      private Undertow server;

      @Override
      public int start(BiConsumer<HttpServletRequest, HttpServletResponse> handler) throws Exception {
        try {
          DeploymentInfo servletBuilder = Servlets.deployment()
              .setClassLoader(getClass().getClassLoader())
              .setContextPath("/")
              .setDeploymentName("verification-servlet.war")
              .addServlets(
                  Servlets.servlet("VerificationServlet", HttpServlet.class, () -> new ImmediateInstanceHandle<>(new HttpServlet() {
                    @Override
                    protected void service(HttpServletRequest req, HttpServletResponse resp) {
                      handler.accept(req, resp);
                    }
                  })).setAsyncSupported(true).addMapping("/*")
              );

          DeploymentManager manager = Servlets.defaultContainer().addDeployment(servletBuilder);
          manager.deploy();

          server = Undertow.builder()
              .addHttpListener(0, "localhost")
              .setHandler(manager.start())
              .build();

          server.start();

          return ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
        } catch (Exception e) {
          e.printStackTrace();
          throw e;
        }
      }

      @Override
      public void stop() throws Exception {
        server.stop();
      }
    }

    return new UndertowServer();
  }
}

