/************************************************************************
 * Licensed under Public Domain (CC0)                                    *
 *                                                                       *
 * To the extent possible under law, the person who associated CC0 with  *
 * this code has waived all copyright and related or neighboring         *
 * rights to this code.                                                  *
 *                                                                       *
 * You should have received a copy of the CC0 legalcode along with this  *
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.*
 ************************************************************************/
package org.reactivestreams.servlet.test.jetty;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.reactivestreams.servlet.test.WithVerificationServer;
import org.testng.annotations.AfterClass;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.BiConsumer;

public interface WithJettyServer extends WithVerificationServer {
  @Override
  default VerificationServer createServer() {

    class JettyServer implements VerificationServer {
      private final Server server = new Server(0);

      @Override
      public int start(BiConsumer<HttpServletRequest, HttpServletResponse> handler) throws Exception {
        server.setHandler(new AbstractHandler() {
          @Override
          public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
              throws IOException, ServletException {
            handler.accept(request, response);
          }
        });
        server.start();
        return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
      }

      @AfterClass
      public void stop() throws Exception {
        server.stop();
      }
    }

    return new JettyServer();
  }
}

