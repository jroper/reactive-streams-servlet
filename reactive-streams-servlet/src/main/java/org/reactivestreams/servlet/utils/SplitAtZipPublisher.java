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
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * A publisher that splits another publisher into substreams, using a predicate.
 *
 * The elements that match the predicate are zipped with their subsequence substreams
 * using a zipper function.
 */
class SplitAtZipPublisher<T, U> implements RichPublisher<U> {

  private final Publisher<T> splitted;
  private final Predicate<T> splitWhen;
  private final BiFunction<T, Publisher<T>, U> zipper;

  public SplitAtZipPublisher(Publisher<T> splitted, Predicate<T> splitWhen, BiFunction<T, Publisher<T>, U> zipper) {
    this.splitted = splitted;
    this.splitWhen = splitWhen;
    this.zipper = zipper;
  }

  @Override
  public void subscribe(Subscriber<? super U> subscriber) {
    Objects.requireNonNull(subscriber, "Subscriber passed to subscribe must not be null");
    splitted.subscribe(new SplitAtZipSubscriber(subscriber));
  }

  private class SplitAtZipSubscriber implements Subscriber<T>, Subscription {
    private Subscriber<? super U> subscriber;
    private final NonBlockingMutexExecutor mutex = new NonBlockingMutexExecutor();
    private final Queue<Object> queue = new ArrayDeque<>();

    public SplitAtZipSubscriber(Subscriber<? super U> subscriber) {
      this.subscriber = subscriber;
    }

    private Subscription subscription = null;
    private long demand;
    private long upstreamDemand;
    private boolean finished;

    private Subscriber<? super T> subSubscriber;
    // A place for holding the first element of the queue after its had the splitWhen
    // predicate invoked on it.
    private T nextElement;
    private boolean waitingForSubSubscriber;
    private long subSubscriberDemand;

    @Override
    public void onSubscribe(Subscription s) {
      Objects.requireNonNull(s, "Subscription passed to onSubscribe must not be null");
      mutex.execute(() -> {
        this.subscription = s;
        subscriber.onSubscribe(this);
      });
    }

    @Override
    public void onNext(T t) {
      Objects.requireNonNull(t, "Element passed to onNext must not be null");
      mutex.execute(() -> {
        if (!finished) {
          upstreamDemand = decrementDemand(upstreamDemand);
          // If there's no sub subscriber and no next element, that means this is
          // the first element we have ever seen. Set this element as the next
          // element.
          if (subSubscriber == null && nextElement == null && !waitingForSubSubscriber) {
            // The stream is effectively always split at the first element,
            // so we the result of the predicate on it is irrelevant, but we run
            // it anyway in case the predicate is stateful (eg, it could be
            // implementing a grouping).
            applyPredicate(t);
            nextElement = t;
            drainQueue();
          } else {
            queue.add(t);
            drainQueue();
          }
        }
      });
    }

    private boolean applyPredicate(T element) {
      try {
        return splitWhen.test(element);
      } catch (RuntimeException e) {
        handleCallbackError(e);
        return false;
      }
    }

    private void handleCallbackError(Throwable t) {
      if (subSubscriber != null) {
        subSubscriber.onError(t);
        subSubscriber = null;
      }
      subscriber.onError(t);
      subscription = null;
      queue.clear();
      finished = true;
    }

    private void drainQueue() {
      if (!queue.isEmpty() || nextElement != null) {
        if (subSubscriber == null) {
          // We have no subscriber, see if we need to publish a new publisher
          maybePublishNewPublisher();
        } else {
          // Check if we've got a nextElement to publish
          if (nextElement != null) {
            if (subSubscriberDemand > 0) {
              subSubscriber.onNext(nextElement);
              nextElement = null;
              subSubscriberDemand = decrementDemand(subSubscriberDemand);
            }
          } else {
            while (!queue.isEmpty() && subSubscriberDemand > 0 && subSubscriber != null) {
              Object element = queue.poll();
              if (!handleCompleteOrError(element)) {
                T t = (T) element;
                if (!maybeSplitStream(t)) {
                  subSubscriber.onNext(t);
                  subSubscriberDemand = decrementDemand(subSubscriberDemand);
                }
              }
            }

            // Try to eagerly terminate the substream without demand.
            if (!finished && nextElement == null && subSubscriber != null && !queue.isEmpty() && subSubscriberDemand == 0) {
              if (handleCompleteOrError(queue.peek())) {
                queue.remove();
              } else if (maybeSplitStream((T) queue.peek())) {
                queue.remove();
              }
            }
          }
        }
      }
    }

    private boolean maybeSplitStream(T element) {
      if (applyPredicate(element)) {
        nextElement = element;
        subSubscriber.onComplete();
        subSubscriber = null;
        subSubscriberDemand = 0;
        // Need to signal for upstream demand again in case it's dropped
        // below the outer streams demand.
        maybeSignalUpstreamDemand(demand);
        maybePublishNewPublisher();
        return true;
      } else {
        return false;
      }
    }

    private void maybeSignalUpstreamDemand(long demand) {
      if (demand > upstreamDemand) {
        subscription.request(demand - upstreamDemand);
        upstreamDemand = demand;
      }
    }

    private long decrementDemand(long demand) {
      if (demand < Long.MAX_VALUE) {
        return demand - 1;
      } else {
        return demand;
      }
    }

    private boolean handleCompleteOrError(Object element) {
      if (element == COMPLETE) {
        subscription = null;
        finished = true;
        subSubscriber.onComplete();
        subSubscriber = null;
        subscriber.onComplete();
        return true;
      } else if (element instanceof ErrorHolder) {
        subscription = null;
        finished = true;
        subSubscriber.onError(((ErrorHolder) element).error);
        subSubscriber = null;
        subscriber.onError(((ErrorHolder) element).error);
        return true;
      }
      return false;
    }

    private void maybePublishNewPublisher() {
      if (!waitingForSubSubscriber && demand > 0) {
        demand = decrementDemand(demand);
        try {
          U toPublish = zipper.apply(nextElement, new SubPublisher());
          nextElement = null;
          subscriber.onNext(toPublish);
          waitingForSubSubscriber = true;
        } catch (RuntimeException e) {
          handleCallbackError(e);
        }
      }
    }

    @Override
    public void onError(Throwable t) {
      Objects.requireNonNull(t, "Exception passed to onError must not be null");
      mutex.execute(() -> {
        if (!finished) {
          if (subSubscriber == null && nextElement == null && !waitingForSubSubscriber) {
            // Error without a single element being received, so there's no substream, just forward.
            subscriber.onError(t);
            subscription = null;
            finished = true;
          } else {
            queue.add(new ErrorHolder(t));
            drainQueue();
          }
        }
      });
    }

    @Override
    public void onComplete() {
      mutex.execute(() -> {
        if (!finished) {
          if (subSubscriber == null && nextElement == null && !waitingForSubSubscriber) {
            // Complete without a single element being received, so there's no substream, just forward.
            subscriber.onComplete();
            subscription = null;
            finished = true;
          } else {
            queue.add(COMPLETE);
            drainQueue();
          }
        }
      });
    }

    @Override
    public void request(long n) {
      mutex.execute(() -> {
        if (!finished) {
          demand = updateDownstreamDemand("Outer subscriber", demand, n);
          drainQueue();
        }
      });
    }

    private long updateDownstreamDemand(String name, long existing, long requested) {
      if (requested <= 0) {
        finished = true;
        queue.clear();
        subscription = null;
        Exception error = new IllegalArgumentException(name + " made non-positive subscription request");
        if (subSubscriber != null) {
          subSubscriber.onError(error);
          subSubscriber = null;
        }
        subscriber.onError(error);
        return 0;
      } else {
        existing += requested;
        if (existing < 0) {
          existing = Long.MAX_VALUE;
        }
        maybeSignalUpstreamDemand(existing);
        return existing;
      }
    }

    @Override
    public void cancel() {
      mutex.execute(() -> {
        if (!finished) {
          if (subSubscriber != null) {
            subSubscriber.onError(new CancellationException("Outer subscriber cancelled stream"));
            subSubscriber = null;
          }
          subscription.cancel();
          subscription = null;
          subscriber = null;
          queue.clear();
          finished = true;
        }
      });
    }

    private class SubPublisher implements Publisher<T>, Subscription {
      private Subscriber<? super T> subscriber = null;
      @Override
      public void subscribe(Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber, "Subscriber passed to subscribe must not be null");
        mutex.execute(() -> {
          if (this.subscriber != null) {
            subscriber.onSubscribe(new Subscription() {
              @Override public void request(long n) { }
              @Override public void cancel() { }
            });
            subscriber.onError(new IllegalStateException("This publisher only supports one subscriber"));
          } else {
            this.subscriber = subscriber;
            SplitAtZipSubscriber.this.subSubscriber = subscriber;
            waitingForSubSubscriber = false;
            this.subscriber.onSubscribe(this);
            subSubscriberDemand = 0;
            // The stream may be complete or in error, so try to drain the queue
            drainQueue();
          }
        });
      }

      @Override
      public void request(long n) {
        mutex.execute(() -> {
          if (this.subscriber == SplitAtZipSubscriber.this.subSubscriber) {
            subSubscriberDemand = updateDownstreamDemand("Sub subscriber", subSubscriberDemand, n);
            drainQueue();
          }
        });
      }

      @Override
      public void cancel() {
        mutex.execute(() -> {
          if (this.subscriber == SplitAtZipSubscriber.this.subSubscriber && !finished) {
            finished = true;
            subscription.cancel();
            subscription = null;
            this.subscriber = null;
            SplitAtZipSubscriber.this.subSubscriber = null;
            SplitAtZipSubscriber.this.subscriber.onError(new CancellationException("Sub subscriber cancelled stream"));
          }
        });
      }
    }
  }

  private static final Object COMPLETE = new Object();
  private static final class ErrorHolder {
    final Throwable error;

    ErrorHolder(Throwable error) {
      this.error = error;
    }
  }

  public static class CancellationException extends Exception {
    CancellationException(String message) {
      super(message);
    }
  }
}
