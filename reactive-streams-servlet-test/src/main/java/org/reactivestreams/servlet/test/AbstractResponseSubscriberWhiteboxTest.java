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
package org.reactivestreams.servlet.test;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.Callback;
import org.reactivestreams.Subscriber;
import org.reactivestreams.servlet.ResponseSubscriber;
import org.reactivestreams.tck.SubscriberWhiteboxVerification;
import org.testng.annotations.*;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public abstract class AbstractResponseSubscriberWhiteboxTest extends SubscriberWhiteboxVerification<ByteBuffer> implements WithVerificationServer {

  private VerificationServer server;
  private HttpClient client;
  private int port;
  private volatile CompletableFuture<Subscriber<ByteBuffer>> nextSubscriber;
  private volatile AsyncContext currentAsyncContext;
  private volatile Throwable lastPublisherError;

  public AbstractResponseSubscriberWhiteboxTest() {
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
          currentAsyncContext = request.startAsync();
          if (response.getOutputStream().isReady()) {
            response.flushBuffer();
          }
          nextSubscriber.complete(new ResponseSubscriber(currentAsyncContext) {
            @Override
            protected void onPublisherError(Throwable t) {
              lastPublisherError = t;
            }
          });
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
  public Subscriber<ByteBuffer> createSubscriber(WhiteboxSubscriberProbe<ByteBuffer> probe) {
    currentAsyncContext = null;
    lastPublisherError = null;
    nextSubscriber = new CompletableFuture<>();
    Request request = client.newRequest("http://localhost:" + port);
    request.send(new ProbeListener(probe, request));
    try {
      Subscriber<ByteBuffer> subscriber = nextSubscriber.get(1, TimeUnit.SECONDS);
      nextSubscriber = null;
      return subscriber;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public ByteBuffer createElement(int element) {
    return ByteBuffer.wrap(new byte[] {(byte) element});
  }

  private class ProbeListener implements SubscriberPuppet, Response.CompleteListener, Response.AsyncContentListener {

    private final WhiteboxSubscriberProbe<ByteBuffer> probe;
    private final Request request;

    public ProbeListener(WhiteboxSubscriberProbe<ByteBuffer> probe, Request request) {
      this.probe = probe;
      this.request = request;
      probe.registerOnSubscribe(this);
    }

    @Override
    public void onComplete(Result result) {
      // The server doesn't distinguish between failures and success,
      // so we capture the error, and inspect it here.
      if (lastPublisherError != null) {
        probe.registerOnError(lastPublisherError);
      } else {
        probe.registerOnComplete();
      }
    }

    @Override
    public void onContent(Response response, ByteBuffer content, Callback callback) {
      while (content.hasRemaining()) {
        probe.registerOnNext(ByteBuffer.wrap(new byte[] { content.get() }));
      }
      callback.succeeded();
    }

    @Override
    public void triggerRequest(long elements) {
      // TCP will automatically do this
    }

    @Override
    public void signalCancel() {
      request.abort(new RuntimeException("Cancelled"));

      // We abort the response, which should close the connection,
      // but for some reason Jetty doesn't seem to detect this until
      // it attempts to write to the connection. So, we do some writes.
      try {
        ServletOutputStream stream = currentAsyncContext.getResponse().getOutputStream();
        while (stream.isReady()) {
          stream.write(0);
          stream.isReady();
          stream.flush();
        }
      } catch (IOException e) {
        // Ignore, we expect to get an exception
      }
    }
  }
}
