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
package org.reactivestreams.servlet.utils;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.function.Function;

class MapConcatPublisher<T, U> implements RichPublisher<U> {
  private final Publisher<T> mapped;
  private final Function<T, Iterable<U>> mapper;

  MapConcatPublisher(Publisher<T> mapped, Function<T, Iterable<U>> mapper) {
    this.mapped = mapped;
    this.mapper = mapper;
  }

  @Override
  public void subscribe(Subscriber<? super U> s) {
    Objects.requireNonNull(s, "Subscriber passed to subscribe must not be null");
    mapped.subscribe(new MapConcatSubscriber(s));
  }

  private class MapConcatSubscriber implements Subscriber<T>, Subscription {
    private Subscriber<? super U> subscriber;
    private final NonBlockingMutexExecutor mutex = new NonBlockingMutexExecutor();
    private final Queue<U> toPublish = new ArrayDeque<>();
    private final Queue<Object> toConsume = new ArrayDeque<>();
    private Subscription subscription = null;
    private long downstreamDemand = 0;
    private long upstreamDemand = 0;
    private boolean finished;

    private MapConcatSubscriber(Subscriber<? super U> subscriber) {
      this.subscriber = subscriber;
    }

    @Override
    public void onSubscribe(Subscription s) {
      Objects.requireNonNull(s, "Subscription passed to onSubscribe must not be null");
      mutex.execute(() -> {
        subscription = s;
        subscriber.onSubscribe(this);
      });
    }

    @Override
    public void onNext(T t) {
      Objects.requireNonNull(t, "Element passed to onNext must not be null");
      mutex.execute(() -> {
        if (!finished) {
          if (upstreamDemand != Long.MAX_VALUE) {
            upstreamDemand -= 1;
          }
          toConsume.add(t);
          drainQueues();
        }
      });
    }

    private void drainQueues() {
      while (!finished && downstreamDemand > 0 && !(toPublish.isEmpty() && toConsume.isEmpty())) {
        while (!finished && toPublish.isEmpty() && !toConsume.isEmpty()) {
          Object element = toConsume.remove();
          if (!handleCompleteOrError(element)) {
            try {
              mapper.apply((T) element).forEach(toPublish::add);
            } catch (RuntimeException e) {
              finished = true;
              toPublish.clear();
              toConsume.clear();
              subscriber.onError(e);
              subscriber = null;
              subscription.cancel();
              subscription = null;
            }
          }
        }

        if (!toPublish.isEmpty()) {
          subscriber.onNext(toPublish.remove());
          if (downstreamDemand != Long.MAX_VALUE) {
            downstreamDemand -= 1;
          }
        }
      }

      if (!finished) {
        if (toPublish.isEmpty()) {
          if (toConsume.isEmpty()) {
            // Ensure that upstream demand never reaches zero while downstream is not zero.
            // Also when upstream demand drops to less than half of downstream demand, top it up.
            if ((upstreamDemand == 0 && downstreamDemand > 0) ||
                upstreamDemand < downstreamDemand / 2) {
              long toRequest = downstreamDemand - upstreamDemand;
              subscription.request(toRequest);
              upstreamDemand += toRequest;
            }
          } else {
            // Eager handling of complete or error without demand
            handleCompleteOrError(toConsume.peek());
          }
        }
      }
    }

    private boolean handleCompleteOrError(Object element) {
      if (element == COMPLETE) {
        subscription = null;
        finished = true;
        toPublish.clear();
        toConsume.clear();
        subscriber.onComplete();
        subscriber = null;
        return true;
      } else if (element instanceof ErrorHolder) {
        subscription = null;
        finished = true;
        toPublish.clear();
        toConsume.clear();
        subscriber.onError(((ErrorHolder) element).error);
        subscriber = null;
        return true;
      }
      return false;
    }

    @Override
    public void onError(Throwable t) {
      Objects.requireNonNull(t, "Exception passed to onError must not be null");
      mutex.execute(() -> {
        if (!finished) {
          if (toPublish.isEmpty() && toConsume.isEmpty()) {
            finished = true;
            subscription = null;
            subscriber.onError(t);
            subscriber = null;
          } else {
            toConsume.add(new ErrorHolder(t));
          }
        }
      });
    }

    @Override
    public void onComplete() {
      mutex.execute(() -> {
        if (!finished) {
          if (toPublish.isEmpty() && toConsume.isEmpty()) {
            finished = true;
            subscription = null;
            subscriber.onComplete();
            subscriber = null;
          } else {
            toConsume.add(COMPLETE);
          }
        }
      });
    }

    @Override
    public void request(long n) {
      mutex.execute(() -> {
        if (n <= 0) {
          finished = true;
          toPublish.clear();
          toConsume.clear();
          subscription.cancel();
          subscription = null;
          subscriber.onError(new IllegalArgumentException("Reactive Streams spec 3.9: non-positive subscription request"));
          subscriber = null;
        } else {
          downstreamDemand += n;
          if (downstreamDemand < 0) {
            downstreamDemand = Long.MAX_VALUE;
          }
          drainQueues();
        }
      });
    }

    @Override
    public void cancel() {
      mutex.execute(() -> {
        if (!finished) {
          finished = true;
          toConsume.clear();
          toPublish.clear();
          subscriber = null;
          subscription.cancel();
          subscription = null;
        }
      });
    }
  }

  private static final Object COMPLETE = new Object();

  private static class ErrorHolder {
    ErrorHolder(Throwable error) {
      this.error = error;
    }

    final Throwable error;
  }
}
