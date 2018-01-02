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
import akka.stream.Materializer;
import akka.stream.javadsl.AsPublisher;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import java.util.List;
import java.util.function.Function;

public class MapConcatPublisherVerification extends PublisherVerification<Integer> {

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

  public MapConcatPublisherVerification() {
    super(new TestEnvironment());
  }

  @Override
  public Publisher<Integer> createPublisher(long elements) {
    Publisher<List<Integer>> publisher = Source.range(1, (int) elements)
        .grouped(3)
        .runWith(Sink.asPublisher(AsPublisher.WITH_FANOUT), mat);

    return RichPublisher.enrich(publisher).mapConcat((list) -> list);
  }

  @Override
  public Publisher<Integer> createFailedPublisher() {
    Publisher<Iterable<Integer>> publisher =
        Source.<Iterable<Integer>>failed(new RuntimeException())
          .runWith(Sink.asPublisher(AsPublisher.WITH_FANOUT), mat);

    return RichPublisher.enrich(publisher).mapConcat(Function.identity());
  }
}
