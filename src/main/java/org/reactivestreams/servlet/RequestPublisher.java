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

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Reactive streams publisher that represents a request.
 */
public class RequestPublisher implements Publisher<ByteBuffer> {

  private static final int MAX_SEQUENTIAL_READS = 8;

  private final ServletInputStream inputStream;
  private final int readBufferLimit;

  private final NonBlockingMutexExecutor mutex = new NonBlockingMutexExecutor();

  private volatile Subscriber<? super ByteBuffer> subscriber;
  private volatile long demand = 0;
  private volatile boolean running = true;

  public RequestPublisher(AsyncContext context, int readBufferLimit) throws IOException {
    this.inputStream = context.getRequest().getInputStream();
    this.readBufferLimit = readBufferLimit;
  }

  /**
   * Handle the case where the subscriber cancels.
   *
   * It's not clear exactly what happens when you close a servlet input stream, so this doesn't close the input stream,
   * instead, it does nothing. To insert some custom behavior, such as closing the stream or consuming the remainder of
   * the body, override this callback.
   *
   * Note that consuming the remainder of the body shouldn't be necessary, servlet containers should consume the
   * remainder of the body when the response is committed.
   */
  protected void handleCancel() {
  }

  private void maybeRead() {
    int reads = 0;

    while (running && demand > 0 && inputStream.isReady() && reads < MAX_SEQUENTIAL_READS) {
      reads += 1;
      ByteBuffer buffer = ByteBuffer.allocate(readBufferLimit);
      try {
        int length = inputStream.read(buffer.array());
        if (length == -1) {
          running = false;
          subscriber.onComplete();
          subscriber = null;
        } else {
          buffer.limit(length);
          subscriber.onNext(buffer);
          demand -= 1;
        }
      } catch (IOException e) {
        handleError(e);
      }
    }

    // If data throughput is really high, and the demand is very high (generally not, but in the TCK there is some
    // Integer.MAX_VALUE demand) the loop above can starve all other tasks from running (including, for example,
    // cancel signals). So we limit the number of sequential reads that we do, and if we reach that limit, we resubmit
    // to do any further reads, but giving the opportunity for other tasks to execute.
    if (reads == MAX_SEQUENTIAL_READS) {
      mutex.execute(this::maybeRead);
    }
  }

  private void handleError(Throwable t) {
    if (running) {
      running = false;
      subscriber.onError(t);
      subscriber = null;
    }
  }

  @Override
  public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
    if (subscriber == null) {
      throw new NullPointerException("Subscriber passed to subscribe must not be null");
    }
    mutex.execute(() -> {
      if (this.subscriber == null) {
        this.subscriber = subscriber;
        inputStream.setReadListener(new Listener());
        subscriber.onSubscribe(new RequestSubscription());
      } else {
        subscriber.onSubscribe(new Subscription() {
          @Override
          public void request(long n) {
          }
          @Override
          public void cancel() {
          }
        });
        subscriber.onError(new IllegalStateException("This publisher only supports one subscriber"));
      }
    });
  }

  private class RequestSubscription implements Subscription {
    @Override
    public void request(long n) {
      mutex.execute(() -> {
        if (n <= 0) {
          subscriber.onError(new IllegalArgumentException("Reative streams 3.9 spec violation: non-positive subscription request"));
        } else if (demand == Long.MAX_VALUE) {
          // Ignore, already demanded the maximum
        } else {
          demand += n;
          if (demand < 0) {
            demand = Long.MAX_VALUE;
          }
          maybeRead();
        }
      });
    }

    @Override
    public void cancel() {
      mutex.execute(() -> {
        subscriber = null;
        if (running) {
          running = false;
          handleCancel();
        }
      });
    }
  }

  private class Listener implements ReadListener {
    @Override
    public void onDataAvailable() throws IOException {
      mutex.execute(RequestPublisher.this::maybeRead);
    }

    @Override
    public void onAllDataRead() throws IOException {
      mutex.execute(() -> {
        if (running) {
          running = false;
          subscriber.onComplete();
          subscriber = null;
        }
      });
    }

    @Override
    public void onError(Throwable t) {
      mutex.execute(() -> handleError(t));
    }
  }

}
