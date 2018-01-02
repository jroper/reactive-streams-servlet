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

import java.util.Objects;
import java.util.function.Function;

class MapPublisher<T, U> implements RichPublisher<U> {

  private final Publisher<T> publisher;
  private final Function<T, U> mapper;

  public MapPublisher(Publisher<T> publisher, Function<T, U> mapper) {
    this.publisher = publisher;
    this.mapper = mapper;
  }

  @Override
  public void subscribe(Subscriber<? super U> subscriber) {
    Objects.requireNonNull(subscriber,"Subscriber passed to subscribe must not be null");
    publisher.subscribe(new MapSubscriber(subscriber));
  }

  private class MapSubscriber implements Subscriber<T>, Subscription {

    private Subscriber<? super U> subscriber;
    private final NonBlockingMutexExecutor mutex = new NonBlockingMutexExecutor();
    private boolean finished = false;
    private Subscription subscription;

    MapSubscriber(Subscriber<? super U> subscriber) {
      this.subscriber = subscriber;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      Objects.requireNonNull(subscription, "Subscription passed to onSubscribe must not be null");
      mutex.execute(() -> {
        this.subscription = subscription;
        subscriber.onSubscribe(this);
      });
    }

    @Override
    public void onNext(T t) {
      Objects.requireNonNull(t, "Element passed to onNext must not be null");
      mutex.execute(() -> {
        if (!finished) {
          try {
            subscriber.onNext(mapper.apply(t));
          } catch (RuntimeException e) {
            finished = true;
            subscriber.onError(e);
            subscriber = null;
            subscription.cancel();
            subscription = null;
          }
        }
      });
    }

    @Override
    public void onError(Throwable t) {
      Objects.requireNonNull(t, "Exception passed to onError must not be null");
      mutex.execute(() -> {
        if (!finished) {
          finished = true;
          subscription = null;
          subscriber.onError(t);
          subscriber = null;
        }
      });
    }

    @Override
    public void onComplete() {
      mutex.execute(() -> {
        if (!finished) {
          finished = true;
          subscription = null;
          subscriber.onComplete();
          subscriber = null;
        }
      });
    }

    @Override
    public void request(long n) {
      mutex.execute(() -> {
        if (!finished) {
          subscription.request(n);
        }
      });
    }

    @Override
    public void cancel() {
      mutex.execute(() -> {
        if (!finished) {
          finished = true;
          subscriber = null;
          subscription.cancel();
          subscription = null;
        }
      });
    }
  }
}
