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

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Provides a number of utility methods on {@link Publisher} for transforming
 * a stream.
 *
 * Use by wrapping a publisher with {@link #enrich(Publisher)}.
 */
public interface RichPublisher<T> extends Publisher<T> {

  /**
   * Map the published elements using the given mapper function.
   *
   * @param mapper The mapper function.
   * @return A publisher that emits the mapped elements.
   */
  default <U> RichPublisher<U> map(Function<T, U> mapper) {
    return new MapPublisher<>(this, mapper);
  }

  /**
   * Map the published elements into a list using the given mapper function.
   *
   * The mapping function is only executed on each upstream element when there
   * is demand from downstream for those elements. This means this publisher
   * will never buffer more than the number of elements emitted by a single
   * invocation of the mapper.
   *
   * An error thrown by the mapper will cause any as yet unmapped elements to be
   * dropped.
   *
   * @param mapper The mapper function.
   * @return A publisher that emits the mapped elements.
   */
  default <U> RichPublisher<U> mapConcat(Function<T, Iterable<U>> mapper) {
    return new MapConcatPublisher<>(this, mapper);
  }

  /**
   * Split a stream into substreams elements that match the given predicate, and
   * then zip the splitted element with the given publisher of sub stream elements.
   *
   * This is useful for when a stream consists of headers followed by sub streams.
   *
   * The sub streams must be subscribed to, even if they are expected to be empty,
   * to ensure the stream progresses to the next sub stream.
   *
   * Cancellation of either the outer stream, or the sub streams, will trigger the
   * immediate cancellation of the entire stage.
   *
   * The first element will trigger the emission of a sub stream regardless of
   * whether it matches the predicate or not.
   *
   * @param splitAt The predicate. Each element that matches will trigger the
   *                emission of a new sub stream.
   * @param zipper The function to zip the elements matched by the predicate with
   *               their associated sub streams.
   * @return A publisher that emits the zipped sub streams.
   */
  default <U> RichPublisher<U> splitAtAndZip(Predicate<T> splitAt, BiFunction<T, Publisher<T>, U> zipper) {
    return new SplitAtZipPublisher<>(this, splitAt, zipper);
  }

  /**
   * Execute the given callback when this stream completes.
   *
   * The callback will not be executed if the stream terminates with an error,
   * and it may not be executed if downstream cancels.
   *
   * If the callback throws an exception, downstream will be terminated with
   * an error.
   *
   * @param callback The callback to execute.
   * @return A publisher that applies the callback.
   */
  default RichPublisher<T> onComplete(Runnable callback) {
    return new OnCompletePublisher<>(this, callback);
  }

  /**
   * Enrich the given publisher with the RichPublisher operations.
   */
  static <T> RichPublisher<T> enrich(Publisher<T> publisher) {
    return publisher::subscribe;
  }
}
