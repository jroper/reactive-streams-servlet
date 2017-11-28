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

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import javax.servlet.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

/**
 * Reactive streams subscriber that represents a response.
 *
 * This subscriber only requests one element at a time, and only if the attached {@link ServletOutputStream} is ready to
 * receive data in a non blocking way. It may be necessary to insert a buffer between this and and a publisher to
 * achieve high through puts.
 *
 * Errors from the {@link org.reactivestreams.Publisher} result in the {@link AsyncContext} being completed, after that
 * {@link #onPublisherError} is invoked - this can be overridden to insert behaviour such as logging or clean up.
 *
 * Errors from the {@link ServletOutputStream} result in the subscription being cancelled, followed by
 * {@link #onOutputStreamError} being invoked - this can be overridden to insert behaviour such as logging or clean up.
 */
public class ResponseSubscriber implements Subscriber<ByteBuffer> {

  private final AsyncContext context;
  private final ServletOutputStream outputStream;

  private final Executor mutex = new NonBlockingMutexExecutor();

  private volatile Subscription subscription;
  private volatile State state = State.IDLE;

  public ResponseSubscriber(AsyncContext context) throws IOException {
    this.context = context;
    this.outputStream = context.getResponse().getOutputStream();
  }

  /**
   * Invoked when a downstream error occurs, ie, when an error writing to the servlet
   * output stream occurs.
   *
   * Override to insert error handling for downstream writing errors, such as logging.
   *
   * By default this does nothing.
   *
   * This method will be invoked at most once.
   *
   * @param t The error that occurred.
   */
  protected void onOutputStreamError(Throwable t) {
  }

  /**
   * Invoked when an upstream error occurs, ie, when an error is received from the publisher.
   *
   * Override to insert error handling for downstream writing errors, such as logging.
   *
   * By default this does nothing.
   *
   * @param t The error that occurred.
   */
  protected void onPublisherError(Throwable t) {
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    if (subscription == null) {
      throw new NullPointerException("Subscription passed to onSubscribe must not be null");
    }
    mutex.execute(() -> {
      if (this.subscription == null) {
        this.subscription = subscription;
        outputStream.setWriteListener(new SubscriberWriteListener());
        context.addListener(new SubscriberAsyncListener());
        maybeRequest();
      } else {
        subscription.cancel();
      }
    });
  }

  @Override
  public void onNext(ByteBuffer item) {
    if (item == null) {
      throw new NullPointerException("Element passed to onNext must not be null");
    }
    mutex.execute(() -> {
      state = State.IDLE;
      try {
        if (item.hasArray()) {
          outputStream.write(item.array(), item.arrayOffset(), item.remaining());
        } else {
          byte[] array = new byte[item.remaining()];
          item.get(array);
          outputStream.write(array);
        }
        // Jetty requires isReady to be invoked before invoking flush
        if (outputStream.isReady()) {
          outputStream.flush();
        }
        maybeRequest();
      } catch (IOException e) {
        streamError(e);
      }
    });
  }

  private void maybeRequest() {
    if (outputStream.isReady() && state != State.DEMANDING) {
      subscription.request(1);
      state = State.DEMANDING;
    }
  }

  private void streamError(Throwable t) {
    switch (state) {
      case IDLE:
      case DEMANDING:
        subscription.cancel();
        onOutputStreamError(t);
        state = State.FINISHED;
        break;
      case FINISHED:
        // Already finished, nothing to do.
        break;
    }
  }

  @Override
  public void onError(Throwable throwable) {
    if (throwable == null) {
      throw new NullPointerException("Exception passed to onError must not be null");
    }
    mutex.execute(() -> {
      switch (state) {
        case IDLE:
        case DEMANDING:
          onPublisherError(throwable);
          context.complete();
          state = State.FINISHED;
          break;
        case FINISHED:
          // Already finished, nothing to do.
          break;
      }
    });
  }

  @Override
  public void onComplete() {
    mutex.execute(() -> {
      switch (state) {
        case IDLE:
        case DEMANDING:
          context.complete();
          state = State.FINISHED;
          break;
        case FINISHED:
          // Already finished, nothing to do.
          break;
      }
    });
  }

  private class SubscriberWriteListener implements WriteListener {
    @Override
    public void onWritePossible() throws IOException {
      mutex.execute(() -> {
        switch (state) {
          case IDLE:
            subscription.request(1);
            state = State.DEMANDING;
            break;
          default:
            // Nothing to do
            break;
        }
      });
    }

    @Override
    public void onError(Throwable t) {
      mutex.execute(() -> streamError(t));
    }
  }

  private void requestComplete() {
    switch (state) {
      case IDLE:
      case DEMANDING:
        subscription.cancel();
        state = State.FINISHED;
        break;
      case FINISHED:
        // Already finished, nothing to do.
        break;
    }
  }

  private class SubscriberAsyncListener implements AsyncListener {
    @Override
    public void onComplete(AsyncEvent event) throws IOException {
      mutex.execute(ResponseSubscriber.this::requestComplete);
    }

    @Override
    public void onTimeout(AsyncEvent event) throws IOException {
      mutex.execute(ResponseSubscriber.this::requestComplete);
    }

    @Override
    public void onError(AsyncEvent event) throws IOException {
      mutex.execute(() -> streamError(event.getThrowable()));
    }

    @Override
    public void onStartAsync(AsyncEvent event) throws IOException {
    }
  }

  private enum State {
    IDLE,
    DEMANDING,
    FINISHED
  }
}
