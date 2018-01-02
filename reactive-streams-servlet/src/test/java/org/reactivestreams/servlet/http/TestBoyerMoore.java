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

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import java.nio.ByteBuffer;
import java.util.*;

public class TestBoyerMoore {

  @Test
  public void singleBufferMatch() {
    assertEquals(boyerMore("hello").search(createString("abchellodef")), 3);
  }

  @Test
  public void singleBufferMatchStart() {
    assertEquals(boyerMore("hello").search(createString("hellodef")), 0);
  }

  @Test
  public void singleBufferMatchEnd() {
    assertEquals(boyerMore("hello").search(createString("abchello")), 3);
  }

  @Test(expectedExceptions = MultipartParser.NotEnoughDataException.class)
  public void singleBufferNoMatch() {
    boyerMore("hello").search(createString("adcdefghi"));
  }

  private static MultipartParser.BoyerMoore boyerMore(String needle) {
    return new MultipartParser.BoyerMoore(needle.getBytes());
  }

  private static Deque<ByteBuffer> createString(String... text) {
    Deque<ByteBuffer> string = new ArrayDeque<>();
    for (String part: text) {
      string.add(ByteBuffer.wrap(part.getBytes()));
    }
    return string;
  }

  /**
   * Generates a random list of byte buffers, ensuring the needle appears somewhere in there, and runs
   * the test.
   */
  @Test(invocationCount = 1000)
  public void randomTest() {
    long seed = System.nanoTime();
    try {
      doRandomTest(seed);
    } catch (Throwable t) {
      System.out.println("Test failed for seed " + seed);
      throw t;
    }
  }

  private void doRandomTest(long seed) {
    Random random = new Random(seed);
    int needleLength = random.nextInt(10) + 10;

    byte[] needle = new byte[needleLength];
    random.nextBytes(needle);

    int stringLength = random.nextInt(1000) + 100;
    int needlePos = random.nextInt(stringLength - needleLength);

    Deque<ByteBuffer> string = new ArrayDeque<>();
    int offset = 0;
    while (len(string) < stringLength) {
      int bufferSize = Math.min(random.nextInt(stringLength), stringLength - offset);
      ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
      if (offset > needlePos + needleLength || offset + bufferSize < needlePos) {
        random.nextBytes(buffer.array());
      } else {
        if (needlePos > offset) {
          nextBytes(random, buffer.array(), 0, needlePos - offset);
        }

        System.arraycopy(
            needle, Math.max(0, offset - needlePos),
            buffer.array(), Math.max(0, needlePos - offset),
            Math.min(
                Math.min(needleLength, bufferSize),
                Math.min(needlePos - offset + needleLength, bufferSize + offset - needlePos)
            )
        );

        if (bufferSize + offset > needlePos + needleLength) {
          nextBytes(random, buffer.array(), needlePos - offset + needleLength,
              bufferSize + offset - (needlePos + needleLength));
        }
      }
      string.add(buffer);
      offset += bufferSize;
    }

    int actualPos = naiveSearch(needle, stringLength, string);
    if (actualPos != needlePos) {
      // Incredibly rare that this would happen, but possible. Log so we can notice if there's a problem.
      System.out.println("For seed " + seed + " got needle at position " + actualPos + " instead of " + needlePos);
    }

    int boyerMoorePos = new MultipartParser.BoyerMoore(needle).search(string);
    assertEquals(boyerMoorePos, actualPos);
  }

  private int naiveSearch(byte[] needle, int stringLength, Deque<ByteBuffer> haystack) {
    for (int i = 0; i < stringLength - needle.length; i++) {
      int j = 0;
      while (needle[j] == byteAt(haystack, i + j)) {
        j++;
        if (j == needle.length) {
          return i;
        }
      }
    }
    return -1;
  }

  private static int len(Deque<ByteBuffer> string) {
    int stringlen = 0;
    for (ByteBuffer buffer: string) {
      stringlen += buffer.remaining();
    }
    return stringlen;
  }

  private static byte byteAt(Deque<ByteBuffer> string, int i) {
    for (ByteBuffer buffer: string) {
      if (i < buffer.remaining()) {
        return buffer.get(i);
      } else {
        i -= buffer.remaining();
      }
    }
    throw new IndexOutOfBoundsException("" + i);
  }

  private static void nextBytes(Random random, byte[] bytes, int offset, int length) {
    for (int i = offset; i < offset + length;) {
      for (int rnd = random.nextInt(), n = Math.min(length + offset - i, 4);  n-- > 0; rnd >>= 8) {
        bytes[i++] = (byte) rnd;
      }
    }
  }


}
