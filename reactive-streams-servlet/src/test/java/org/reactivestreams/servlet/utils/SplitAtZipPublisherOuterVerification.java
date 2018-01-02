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

import akka.Done;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.AsPublisher;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SplitAtZipPublisherOuterVerification extends PublisherVerification<Integer> {

  private ActorSystem system;
  private Materializer mat;

  @BeforeClass
  public void setup() {
    system = ActorSystem.create();
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public void tearDown() {
    system.terminate();
  }

  public SplitAtZipPublisherOuterVerification() {
    super(new TestEnvironment(100, 100));
  }

  @BeforeMethod
  public void resetSubstreams() {
    substreams = new ArrayList<>();
  }

  @AfterMethod
  public void verifySubstreamsComplete() throws Exception {
    for (CompletionStage<Done> substream: substreams) {
      substream.toCompletableFuture().get(TestEnvironment.envDefaultTimeoutMillis(), TimeUnit.MILLISECONDS);
    }
    substreams = null;
  }

  private List<CompletionStage<Done>> substreams;

  @Override
  public Publisher<Integer> createPublisher(long elements) {
    Publisher<Integer> publisher = Source.range(1, (int) elements)
        .mapConcat(i -> {
          Integer[] results = new Integer[Math.min(i + 1, 10)];
          Arrays.fill(results, i);
          return Arrays.asList(results);
        })
        .runWith(Sink.asPublisher(AsPublisher.WITH_FANOUT), mat);

    AtomicInteger current = new AtomicInteger(0);
    return RichPublisher.enrich(publisher).splitAtAndZip(
        i -> current.getAndSet(i) != i,
        (i, pub) -> {
          substreams.add(Source.fromPublisher(pub).map(j -> {
            if (!j.equals(i)) {
              throw new IllegalArgumentException("Got " + j + " in a stream that should just contain " + i);
            }
            return j;
          }).runWith(Sink.ignore(), mat));
          return i;
        }
    );
  }

  @Override
  public Publisher<Integer> createFailedPublisher() {
    Publisher<Integer> publisher = Source.<Integer>failed(new RuntimeException())
        .runWith(Sink.asPublisher(AsPublisher.WITH_FANOUT), mat);

    return RichPublisher.enrich(publisher).splitAtAndZip(i -> true, (i, pub) -> i);
  }

}
