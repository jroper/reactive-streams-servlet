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
import org.reactivestreams.Subscriber;
import org.reactivestreams.tck.SubscriberBlackboxVerification;
import org.testng.annotations.*;

import javax.servlet.AsyncContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public abstract class AbstractResponseSubscriberBlackboxTest extends SubscriberBlackboxVerification<ByteBuffer> implements WithVerificationServer {

  private VerificationServer server;
  private HttpClient client;
  private int port;
  private volatile CompletableFuture<Subscriber<ByteBuffer>> nextSubscriber;

  public AbstractResponseSubscriberBlackboxTest() {
    super(ServletTestEnvironment.INSTANCE);
  }

  @BeforeClass
  public void start() throws Exception {
    server = createServer();
    port = server.start((request, response) -> {
      try {
        if (nextSubscriber == null) {
          response.sendError(500, "No next subscriber");
        } else {
          AsyncContext context = request.startAsync();
          nextSubscriber.complete(new ResponseSubscriber(context));
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
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
