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

class OnCompletePublisher<T> implements RichPublisher<T> {
  private final Publisher<T> publisher;
  private final Runnable onComplete;

  public OnCompletePublisher(Publisher<T> publisher, Runnable onComplete) {
    this.publisher = publisher;
    this.onComplete = onComplete;
  }

  @Override
  public void subscribe(Subscriber<? super T> subscriber) {
    publisher.subscribe(new Subscriber<T>() {
      @Override
      public void onSubscribe(Subscription s) {
        subscriber.onSubscribe(s);
      }

      @Override
      public void onNext(T t) {
        subscriber.onNext(t);
      }

      @Override
      public void onError(Throwable t) {
        subscriber.onError(t);
      }

      @Override
      public void onComplete() {
        try {
          onComplete.run();
          subscriber.onComplete();
        } catch (RuntimeException e) {
          subscriber.onError(e);
        }
      }
    });
  }
}
