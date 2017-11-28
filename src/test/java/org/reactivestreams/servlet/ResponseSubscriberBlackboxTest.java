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
package org.reactivestreams.servlet;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.reactivestreams.Subscriber;
import org.reactivestreams.tck.SubscriberBlackboxVerification;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ResponseSubscriberBlackboxTest extends SubscriberBlackboxVerification<ByteBuffer> {

  private Server server;
  private HttpClient client;
  private int port;
  private volatile CompletableFuture<Subscriber<ByteBuffer>> nextSubscriber;

  public ResponseSubscriberBlackboxTest() {
    super(ServletTestEnvironment.INSTANCE);
  }

  @BeforeClass
  public void start() throws Exception {
    server = new Server(0);
    server.setHandler(new AbstractHandler() {
      @Override
      public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (nextSubscriber == null) {
          response.sendError(500, "No next subscriber");
        } else {
          AsyncContext context = request.startAsync();
          nextSubscriber.complete(new ResponseSubscriber(context));
        }
      }
    });
    server.start();
    port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  @AfterClass
  public void stop() throws Exception {
    server.stop();
  }

  @BeforeMethod
  public void before() throws Exception {
    client = new HttpClient();
    client.setMaxConnectionsPerDestination(1000);
    client.start();
  }

  @AfterMethod
  public void after() throws Exception {
    client.stop();
  }

  @Override
  public Subscriber<ByteBuffer> createSubscriber() {
    nextSubscriber = new CompletableFuture<>();
    client.newRequest("http://localhost:" + port)
      .send(result -> {});
    try {
      return nextSubscriber.get(1, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public ByteBuffer createElement(int element) {
    return ByteBuffer.wrap(new byte[] {(byte) element});
  }
}
