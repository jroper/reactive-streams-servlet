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

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Attributes;
import akka.stream.Materializer;
import akka.stream.javadsl.AsPublisher;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class SplitAtZipPublisherInnerVerification extends PublisherVerification<Integer> {

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

  public SplitAtZipPublisherInnerVerification() {
    super(new TestEnvironment());
  }

  @Override
  public Publisher<Integer> createPublisher(long elements) {
    Publisher<Integer> publisher = Source.single(-1)
        .concat(Source.range(1, (int) elements))
        .concat(Source.single(-1))
        .runWith(Sink.asPublisher(AsPublisher.WITH_FANOUT), mat);

    Publisher<Publisher<Integer>> split = RichPublisher.enrich(publisher)
        .splitAtAndZip(i -> i == -1, (i, pub) -> {
          if (i != -1) {
            throw new RuntimeException("Bad element passed");
          }
          return pub;
        });

    CompletableFuture<Publisher<Integer>> result = new CompletableFuture<>();
    Source.fromPublisher(split).runForeach(result::complete, mat);
    try {
      return result.get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Publisher<Integer> createFailedPublisher() {
    Publisher<Integer> publisher = Source.from(Arrays.asList(-1, 1))
        .map(i -> {
          if (i > 0) {
            throw new RuntimeException();
          } else {
            return i;
          }
        })
        .runWith(Sink.<Integer>asPublisher(AsPublisher.WITH_FANOUT)
            .withAttributes(Attributes.inputBuffer(1, 1)), mat);

    Publisher<Publisher<Integer>> split = RichPublisher.enrich(publisher)
        .splitAtAndZip(i -> i == -1, (i, pub) -> {
          if (i != -1) {
            throw new RuntimeException("Bad element passed");
          }
          return pub;
        });

    CompletableFuture<Publisher<Integer>> result = new CompletableFuture<>();
    Source.fromPublisher(split).runForeach(result::complete, mat);
    try {
      return result.get(TestEnvironment.envDefaultTimeoutMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
