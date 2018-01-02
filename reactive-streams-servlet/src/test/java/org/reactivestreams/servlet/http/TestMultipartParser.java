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
package org.reactivestreams.servlet.http;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.AsPublisher;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.Sink;
import org.reactivestreams.Publisher;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

public class TestMultipartParser {

  private String boundary = "aabbccddee";
  private String body = String.join("\r\n", "--aabbccddee",
      "Content-Disposition: form-data; name=\"text1\"",
      "",
      "the first text field",
      "--aabbccddee",
      "Content-Disposition: form-data; name=\"text2\"",
      "",
      "the first text field, this time it's particularly long,",
      "spanning several lines so as to trigger it to become streamed.",
      "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz",
      "--aabbccddee",
      "Content-Disposition: form-data; name=noQuotesText1",
      "",
      "text field with unquoted name",
      "--aabbccddee",
      "Content-Disposition: form-data; name=\"file1\"; filename=\"file1.txt\"",
      "Content-Type: text/plain",
      "",
      "the first file",
      "",
      "--aabbccddee",
      "Content-Disposition: form-data; name=\"file2\"; filename=\"file2.txt\"",
      "Content-Type: text/plain",
      "",
      "the second file which is a little bit longer than the",
      "first file for testing purposes",
      "",
      "--aabbccddee--"
  );

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

  @Test
  public void testSingleChunkParse() throws Throwable {
    Map<String, DataPart> parts = parse(getBodyPublisher(body, Integer.MAX_VALUE), boundary, 8192);
    checkResult(parts);
    assertNotStreamed(getPart(parts, "text1"));
  }

  @Test
  public void testMultiChunkParse() throws Throwable {
    Map<String, DataPart> parts = parse(getBodyPublisher(body, 20), boundary, 8192);
    checkResult(parts);
    assertNotStreamed(getPart(parts, "text1"));
  }

  @Test
  public void testSmallChunkParse() throws Throwable {
    Map<String, DataPart> parts = parse(getBodyPublisher(body, 3), boundary, 8192);
    checkResult(parts);
    assertNotStreamed(getPart(parts, "text1"));
  }

  @Test
  public void testParsePreamble() throws Throwable {
    Map<String, DataPart> parts = parse(getBodyPublisher("This is a preamble\r\n" + body, 20), boundary, 8192);
    checkResult(parts);
  }

  @Test
  public void testStreamDataPart() throws Throwable {
    Map<String, DataPart> parts = parse(getBodyPublisher(body, 20), boundary, 200);
    checkResult(parts);
    assertNotStreamed(getPart(parts, "text1"));
    assertWasStreamed(getPart(parts, "text2"));
  }

  @Test(expectedExceptions = MultipartParser.MultipartException.class)
  public void testPrematureEof() throws Throwable {
    parse(getBodyPublisher(
        String.join("\r\n", "--" + boundary,
            "Content-Disposition: form-data; name=\"text1\"",
            "",
            "the first text field",
            "--" + boundary,
            ""),
        20), boundary, 200);
  }

  @Test(expectedExceptions = MultipartParser.MultipartException.class)
  public void testHeaderExceedsMaxBufferSize() throws Throwable {
    parse(getBodyPublisher(
        String.join("\r\n", "--" + boundary,
            "Content-Disposition: form-data; name=\"text1\"",
            "",
            "the first text field",
            "--" + boundary + "--"),
        20), boundary, 20);
  }

  private Publisher<ByteBuffer> getBodyPublisher(String body, int chunkSize) {
    return Source.unfold(body, remaining -> {
      if (remaining.isEmpty()) {
        return Optional.empty();
      } else {
        int splitAt = Math.min(remaining.length(), chunkSize);
        return Optional.of(Pair.create(remaining.substring(splitAt), remaining.substring(0, splitAt)));
      }
    }).map(s -> ByteBuffer.wrap(s.getBytes())).runWith(Sink.asPublisher(AsPublisher.WITH_FANOUT), mat);
  }

  private Map<String, DataPart> parse(Publisher<ByteBuffer> body, String boundary, int maxBufferSize) throws Throwable {
    try {
      List<DataPart> parts = Source.fromPublisher(MultipartParser.parse(body, boundary, maxBufferSize))
          .mapAsync(1, part -> {
            if (part instanceof DataPart) {
              return CompletableFuture.completedFuture((DataPart) part);
            } else if (part instanceof PartPublisher) {
              PartPublisher publisher = (PartPublisher) part;
              return Source.fromPublisher(publisher)
                  .runWith(Sink.fold("",
                      (data, bytes) -> {
                        byte[] buffer = new byte[bytes.remaining()];
                        bytes.get(buffer);
                        return data + new String(buffer);
                      }), mat)
                  .thenApply(data -> new ConsumedStreamedPart(publisher, data));
            } else {
              throw new IllegalArgumentException("Unknown part: " + part);
            }
          }).runWith(Sink.seq(), mat).toCompletableFuture().get(1, TimeUnit.SECONDS);

      Map<String, DataPart> results = new HashMap<>();

      parts.forEach(part -> results.put(part.getName(), part));
      return results;
    } catch (ExecutionException e) {
      throw e.getCause();
    }
  }

  private void checkResult(Map<String, DataPart> result) {
    assertEquals(getPart(result, "text1").getData(), "the first text field");
    assertEquals(getPart(result, "text2").getData(), "the first text field, this time it's particularly long,\r\n" +
        "spanning several lines so as to trigger it to become streamed.\r\n" +
        "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz");
    assertEquals(getPart(result, "noQuotesText1").getData(), "text field with unquoted name");

    DataPart file1 = getPart(result, "file1");
    assertEquals(file1.getData(), "the first file\r\n");
    assertEquals(file1.getSubmittedFileName(), "file1.txt");
    assertWasStreamed(file1);

    DataPart file2 = getPart(result, "file2");
    assertEquals(file2.getData(), "the second file which is a little bit longer than the\r\nfirst file for testing purposes\r\n");
    assertEquals(file2.getSubmittedFileName(), "file2.txt");
    assertWasStreamed(file2);

    assertEquals(result.size(), 5);
  }

  private void assertWasStreamed(DataPart part) {
    assertTrue(part instanceof ConsumedStreamedPart, "Part " + part + " was not streamed.");
  }

  private void assertNotStreamed(DataPart part) {
    assertFalse(part instanceof ConsumedStreamedPart, "Part " + part + " was streamed.");
  }

  private DataPart getPart(Map<String, DataPart> parts, String partName) {
    assertTrue(parts.containsKey(partName), "Parts did not contain " + partName + ", contents was: " + parts);
    return parts.get(partName);
  }

  // A streamed part that has been consumed into a data part.
  private static class ConsumedStreamedPart implements DataPart {
    private final PartPublisher part;
    private final String data;

    private ConsumedStreamedPart(PartPublisher part, String data) {
      this.part = part;
      this.data = data;
    }

    @Override
    public String getData() {
      return data;
    }

    @Override
    public String getSubmittedFileName() {
      return part.getSubmittedFileName();
    }

    @Override
    public String getName() {
      return part.getName();
    }

    @Override
    public String getContentType() {
      return part.getContentType();
    }

    @Override
    public String getHeader(String name) {
      return part.getHeader(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
      return part.getHeaders(name);
    }

    @Override
    public Collection<String> getHeaderNames() {
      return part.getHeaderNames();
    }
  }

}
