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

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.servlet.utils.RichPublisher;

import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * A multipart/form-data parser.
 */
public class MultipartParser {

  /**
   * Parse the given stream of byte buffers into a stream of {@link StreamedPart}.
   *
   * Each {@link StreamedPart} emitted will either be a {@link DataPart}, that is,
   * a part that does not have a file name and has been buffered into memory, or a
   * {@link PartPublisher}, that is a part that implements the {@link Publisher}
   * interface and must be consumed by subscribing to it.
   *
   * Each {@link PartPublisher} emitted must be subscribed to in order for the
   * stream to progress, even if it is going to be ignored.
   *
   * If a {@link PartPublisher} stream is cancelled, the entire stream of parts
   * will be cancelled too.
   *
   * The parser needs to do some buffering to parse part headers and data parts.
   * This buffering is limited by the <code>maxBufferSize</code> parameter. If this
   * buffer size is exceeded while parsing a part header, the stream will terminate
   * with an error. If it's exceeded while buffering a data part, that data part
   * will be emitted as a {@link PartPublisher} instead of as a {@link DataPart}.
   *
   * Regardless of what publisher is passed in to this method, the returned publisher
   * is stateful and cannot be reused.
   *
   * @param publisher The publisher to parse.
   * @param boundary The multipart boundary.
   * @param maxBufferSize The maximum size of data to buffer.
   * @return A publisher of streamed parts.
   */
  public static Publisher<StreamedPart> parse(Publisher<ByteBuffer> publisher, String boundary, int maxBufferSize) {
    Parser parser = new Parser(boundary.getBytes(StandardCharsets.UTF_8), maxBufferSize); // FIXME add charset of boundary

    return RichPublisher.enrich(publisher)
        .mapConcat(parser)
        .onComplete(parser::onComplete)
        .splitAtAndZip(t -> t instanceof AbstractPart, (part, pub) -> {
          if (part instanceof DataPart) {
            pub.subscribe(new IgnoringSubscriber<>());
            return (DataPart) part;
          } else if (part instanceof ContentlessPart) {
            return ((ContentlessPart) part).withPublisher(
                RichPublisher.enrich(pub).map(bytes -> {
                  if (bytes instanceof ByteBuffer) {
                    return (ByteBuffer) bytes;
                  } else {
                    throw new MultipartException("Unrecognized element in byte buffer sub stream");
                  }
                }));
          } else {
            throw new MultipartException("Unrecognized part: " + part);
          }
        });
  }

  public static class MultipartException extends RuntimeException {
    public MultipartException(String message) {
      super(message);
    }

    public MultipartException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static class IgnoringSubscriber<T> implements Subscriber<T> {
    boolean subscribed;
    @Override
    public void onSubscribe(Subscription s) {
      if (!subscribed) {
        subscribed = true;
        s.request(Long.MAX_VALUE);
      } else {
        s.cancel(); // As per RS spec.
      }
    }
    @Override
    public void onNext(Object o) { }
    @Override
    public void onError(Throwable t) { }
    @Override
    public void onComplete() { }
  }

  // Limitations of this implementation:
  // * When streaming parts, chunks will generally be split into two, the first chunk
  //   being the size of the chunk minus the boundary size plus one, the second being
  //   kept back in case it contains a split boundary for the next search. This can be
  //   solved by keeping the entire chunk back, but will require more complex logic
  //   that hasn't been done yet.
  // * The structure for keeping the current chunks is an ArrayDeque. Because this
  //   doesn't support random access (it could, but the API doesn't allow it), each
  //   retrieval requires allocating an iterator. This however is optimised for the
  //   most common case, where the stream is all large chunks and so the the queue
  //   contains one or two elements.
  // * Parsing of headers is done very suboptimally - converting the entire header
  //   into a string, splitting using regular expressions, etc.
  // * Header parsing is not at all strict to the spec (which is not very well
  //   specified anyway). What constitutes correct behavior is often a matter of
  //   opinion, especially with regards to encoding, so this doesn't make any attempt
  //   to be strict.
  // * The spec says the part boundary can contain arbitrary whitespace before the
  //   next new line, in practice no implementations add such whitespace and it is
  //   not supported by this implementation.

  private static class Parser implements Function<ByteBuffer, Iterable<Object>> {

    private final static byte HYPHEN = '-';
    private final static byte CR = '\r';
    private final static byte LF = '\n';
    private final static ByteBuffer CRLF = ByteBuffer.wrap(new byte[] {CR, LF}).asReadOnlyBuffer();

    // Current chunks to parse
    private final Deque<ByteBuffer> currentChunks = new ArrayDeque<>();
    private final BoyerMoore boundarySearch;
    private final BoyerMoore headerEndSearch;
    private final int maxBufferSize;
    private final int boundarySize;
    // The size of the current chunks buffer
    private int currentChunksSize;
    // The amount of memory being buffered (may include data already parsed).
    private int currentMemorySize;
    // Used by both the mapConcat function and the on complete callback,
    // which have no happens before between them, so needs to be volatile.
    private volatile State state = State.IGNORING_PREAMBLE;
    private ContentlessPart currentPart;
    private List<Object> toEmit;

    private Parser(byte[] boundary, int maxBufferSize) {
      byte[] fullBoundary = new byte[4 + boundary.length];
      fullBoundary[0] = CR;
      fullBoundary[1] = LF;
      fullBoundary[2] = HYPHEN;
      fullBoundary[3] = HYPHEN;
      System.arraycopy(boundary, 0, fullBoundary, 4, boundary.length);

      this.boundarySearch = new BoyerMoore(fullBoundary);
      this.headerEndSearch = new BoyerMoore(new byte[] { CR, LF, CR, LF });
      this.maxBufferSize = maxBufferSize;
      this.boundarySize = fullBoundary.length;

      // Initialise current chunks to have a CRLF, this ensures that in the case that
      // there is no preamble, we still match the first boundary, which won't be preceeded
      // with CRLF.
      currentChunks.add(CRLF);
      currentChunksSize = 2;
    }

    @Override
    public Iterable<Object> apply(ByteBuffer byteBuffer) {
      if (state != State.IGNORING_EPILOGUE) {
        currentChunks.add(byteBuffer);
        currentChunksSize += byteBuffer.remaining();
        currentMemorySize += byteBuffer.remaining();

        toEmit = new ArrayList<>(); // TODO only allocate if needed, use Collections.emptyList() otherwise

        try {
          while (state != State.IGNORING_EPILOGUE) {
            switch (state) {
              case IGNORING_PREAMBLE:
                state = ignorePreamble();
                break;
              case PARSING_HEADER:
                state = parsePartHeader();
                break;
              case BUFFERING_DATA_PART:
                state = parseDataPart();
                break;
              case STREAMING_PART:
                state = streamPart();
                break;
            }

          }
        } catch (NotEnoughDataException e) {
          // Ignore
        }


        List<Object> result = toEmit.isEmpty() ? Collections.emptyList() : toEmit;
        toEmit = null;
        return result;
      } else {
        return Collections.emptyList();
      }
    }

    public void onComplete() {
      if (state != State.IGNORING_EPILOGUE) {
        throw new MultipartException("Premature end of input encountered before final boundary.");
      }
    }

    private State ignorePreamble() {
      try {
        int index = boundarySearch.search(currentChunks);
        return parseEndOfBoundary(index);
      } catch (NotEnoughDataException e) {
        dropUpToPotentialBoundary();
        throw e;
      }
    }

    /**
     * This expects the buffer to start at the start of the boundary
     */
    private State parseEndOfBoundary(int boundaryStart) {
      int startEndOfBoundary = boundaryStart + boundarySize;
      byte nextByte = byteAt(startEndOfBoundary);
      byte byteAfter = byteAt(startEndOfBoundary + 1);
      dropBuffer(startEndOfBoundary + 2);
      if (nextByte == HYPHEN && byteAfter == HYPHEN) {
        return State.IGNORING_EPILOGUE;
      } else if (nextByte == CR && byteAfter == LF) {
        return State.PARSING_HEADER;
      } else {
        throw new MultipartException("Illegal characters after boundary: " + String.format("0x%02X%02X", nextByte, byteAfter));
      }
    }

    private State parsePartHeader() {
      resetMemoryBuffer();
      try {
        int endOfHeader = headerEndSearch.search(currentChunks);

        // Not very efficient, but simple for now.
        String headerString = readString(endOfHeader, StandardCharsets.UTF_8);

        String[] headers = headerString.split("\r\n");
        Map<String, List<String>> headerMap = new HashMap<>();
        for (String header: headers) {
          if (header.length() > 0) {
            String parts[] = header.split(": *",  2);
            String key = parts[0].toLowerCase(Locale.ROOT);
            String value = "";
            if (parts.length > 1) {
              value = parts[1];
            }
            headerMap.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
          }
        }

        List<String> contentDispositions = headerMap.get("content-disposition");
        if (contentDispositions == null) {
          throw new MultipartException("Part without Content-Disposition header");
        }
        String contentDisposition = contentDispositions.get(0);
        // parse it
        if (!contentDisposition.startsWith("form-data")) {
          throw new MultipartException("Non form-data part");
        }
        String[] params = contentDisposition.substring(9).split(";");
        String name = null;
        String filename = null;
        for (String param: params) {
          String trimmed = param.trim();
          if (!trimmed.isEmpty()) {
            String[] keyValue = trimmed.split("=", 2);
            String key = keyValue[0].trim();
            String value = "";
            if (keyValue.length > 1) {
              value = keyValue[1].trim();
            }
            String unquotedValue = value;
            if (value.startsWith("\"") && value.endsWith("\"")) {
              unquotedValue = value.substring(1, value.length() - 1);
            }

            if (key.equals("name")) {
              name = unquotedValue;
            }
            if (key.equals("filename")) {
              filename = unquotedValue;
            }
          }
        }

        currentPart = new ContentlessPart(headerMap, name, filename);
        dropBuffer(endOfHeader + 4);

        if (filename == null) {
          return State.BUFFERING_DATA_PART;
        } else {
          emit(currentPart);
          return State.STREAMING_PART;
        }
      } catch (NotEnoughDataException e) {
        checkBufferSize();
        throw e;
      }
    }

    private State parseDataPart() {
      resetMemoryBuffer();
      try {
        int index = boundarySearch.search(currentChunks);
        String data = readString(index, StandardCharsets.UTF_8); // FIXME parse charset from content type
        State nextState = parseEndOfBoundary(index);
        emit(currentPart.withData(data));
        currentPart = null;
        return nextState;
      } catch (NotEnoughDataException e) {
        if (currentMemorySize > maxBufferSize) {
          emit(currentPart);
          emitUpToPotentialBoundary();
          state = State.STREAMING_PART;
        }
        throw e;
      }
    }

    private State streamPart() {
      try {
        int index = boundarySearch.search(currentChunks);
        emitBuffer(index);
        State nextState = parseEndOfBoundary(0);
        currentPart = null;
        return nextState;
      } catch (NotEnoughDataException e) {
        emitUpToPotentialBoundary();
        throw e;
      }
    }

    private String readString(int length, Charset charset) {
      byte[] bytes = new byte[length]; // todo avoid allocating new buffer per read
      int copied = 0;
      for (ByteBuffer buffer: currentChunks) {
        int toCopy = Math.min(length - copied, buffer.remaining());
        if (toCopy > 0) {
          buffer.mark();
          buffer.get(bytes, copied, toCopy);
          buffer.reset();
          copied += toCopy;
        }
        if (copied == length) {
          break;
        }
      }
      return (copied < 1) ? "" : new String(bytes, charset);
    }

    private void emitBuffer(int size) {
      int leftToEmit = size;
      while (!currentChunks.isEmpty() && leftToEmit > 0) {
        ByteBuffer chunk = currentChunks.pollFirst();
        if (leftToEmit >= chunk.remaining()) {
          emit(chunk);
          leftToEmit -= chunk.remaining();
          currentChunksSize -= chunk.remaining();
        } else {
          chunk.limit(leftToEmit);
          emit(chunk.slice());
          // Does not actually clear, just resets position and limit
          chunk.clear();
          chunk.position(leftToEmit);
          currentChunks.addFirst(chunk.slice());
          currentChunksSize -= leftToEmit;
          leftToEmit = 0;
        }
      }
      if (leftToEmit > 0) {
        throw new ArrayIndexOutOfBoundsException("Did not find " + size + " bytes in current buffer to emit");
      }
    }

    private void dropBuffer(int size) {
      int leftToDrop = size;
      while (!currentChunks.isEmpty() && leftToDrop > 0) {
        ByteBuffer chunk = currentChunks.pollFirst();
        if (leftToDrop >= chunk.remaining()) {
          leftToDrop -= chunk.remaining();
          currentChunksSize -= chunk.remaining();
        } else {
          // Does not actually clear, just resets position and limit
          chunk.position(leftToDrop);
          // Put the sliced chunk back at the front of the queue because we removed it before.
          currentChunks.addFirst(chunk.slice());
          currentChunksSize -= leftToDrop;
          leftToDrop = 0;
        }
      }
      if (leftToDrop > 0) {
        throw new ArrayIndexOutOfBoundsException("Did not find " + size + " bytes in current buffer to drop, was " + leftToDrop + " bytes short");
      }
    }

    private void emitUpToPotentialBoundary() {
      if (currentChunksSize > boundarySize) {
        emitBuffer(currentChunksSize - boundarySize);
      }
    }

    private void dropUpToPotentialBoundary() {
      if (currentChunksSize > boundarySize) {
        dropBuffer(boundarySize - currentChunksSize);
      }
    }

    private void emit(Object object) {
      toEmit.add(object);
    }

    private void resetMemoryBuffer() {
      currentMemorySize = currentChunksSize;
    }

    private byte byteAt(int i) {
      return MultipartParser.byteAt(currentChunks, i);
    }

    private void checkBufferSize() {
      if (currentMemorySize > maxBufferSize) {
        throw new MultipartException("Max buffer size reached (" + maxBufferSize + " bytes) while parsing multipart header");
      }
    }

    private void printBuffer(PrintStream out) {
      out.println("=== PRINTING BUFFER ===");
      currentChunks.forEach(chunk -> {
        byte[] bytes = new byte[chunk.remaining()];
        chunk.mark();
        chunk.get(bytes);
        chunk.reset();
        out.print(new String(bytes, StandardCharsets.UTF_8)); // FIXME use correct charset
      });
      out.println();
      out.println("=== END BUFFER ===");
    }

  }

  private enum State {
    /**
     * A multipart message may (but in the case of multipart/form-data, never actually does)
     * contain a preamble, which should be ignored, and ends when the first boundary is encountered.
     */
    IGNORING_PREAMBLE,
    /**
     * We're currently parsing a part header.
     */
    PARSING_HEADER,
    /**
     * We're currently buffering a data part.
     */
    BUFFERING_DATA_PART,
    /**
     * We're currently streaming a part.
     */
    STREAMING_PART,
    /**
     * We're ignoring the epilogue - ie, we have finished parsing the message.
     */
    IGNORING_EPILOGUE
  }

  /**
   * Boyer-Moore algorithm implementation that searches over a list of buffers.
   *
   * For more details, see the paper: http://www.cs.utexas.edu/users/moore/publications/fstrpos.pdf
   */
  static class BoyerMoore {
    private final byte[] pattern;
    private final int[] delta1;
    private final int[] delta2;

    BoyerMoore(byte[] pattern) {
      this.pattern = pattern;
      this.delta1 = preprocessDelta1(pattern);
      this.delta2 = preprocessDelta2(pattern);
    }

    int search(Deque<ByteBuffer> string) {
      if (pattern.length == 0) {
        return 0;
      }
      int stringlen = len(string);

      int i = pattern.length - 1;

      while (i < stringlen) {
        int j = pattern.length - 1;

        while (byteAt(string, i) == pattern[j]) {
          if (j == 0) {
            return i;
          }
          j--;
          i--;
        }

        i += Integer.max(delta1[byteAt(string, i) & 0xff], delta2[pattern.length - 1 - j]);
      }
      throw NOT_ENOUGH_DATA;
    }

    private static int[] preprocessDelta1(byte[] pattern) {
      int[] delta1 = new int[256];
      Arrays.fill(delta1, pattern.length);
      for (int j = 0; j < pattern.length; j++) {
        delta1[pattern[j] & 0xff] = pattern.length - 1 - j;
      }
      return delta1;
    }

    private static int[] preprocessDelta2(byte[] pattern) {
      int[] delta2 = new int[pattern.length];
      int last = pattern.length;
      for (int i = pattern.length; i > 0; --i) {
        if (isPrefix(pattern, i)) {
          last = i;
        }
        delta2[pattern.length - i] = last - i + pattern.length;
      }
      for (int i = 0; i < pattern.length - 1; ++i) {
        int len = suffixLength(pattern, i);
        delta2[len] = pattern.length - 1 - i + len;
      }
      return delta2;
    }

    private static boolean isPrefix(byte[] pattern, int p) {
      for (int i = p, j = 0; i < pattern.length; i++, j++) {
        if (pattern[i] != pattern[j]) {
          return false;
        }
      }
      return true;
    }

    private static int suffixLength(byte[] pattern, int p) {
      int len = 0;
      int i = p;
      int j = pattern.length - 1;
      while (i >= 0 && pattern[i] == pattern[j]) {
        len++;
        i--;
        j--;
      }
      return len;
    }

    private static int len(Deque<ByteBuffer> string) {
      int stringlen = 0;
      for (ByteBuffer buffer: string) {
        stringlen += buffer.remaining();
      }
      return stringlen;
    }

  }

  private static byte byteAt(Deque<ByteBuffer> string, int i) {
    if (!string.isEmpty()) {

      if (string.size() <= 2) {

        // optimise for a deque two elements to avoid allocating an iterator
        ByteBuffer first = string.getFirst();
        if (i < first.remaining()) {
          return first.get(i);
        }
        i -= first.remaining();

        if (string.size() == 2) {
          ByteBuffer last = string.getLast();
          if (i < last.remaining()) {
            return last.get(i);
          }
        }

      } else {
        for (ByteBuffer buffer: string) {
          if (i < buffer.remaining()) {
            return buffer.get(i);
          } else {
            i -= buffer.remaining();
          }
        }
      }
    }
    throw NOT_ENOUGH_DATA;
  }

  static class NotEnoughDataException extends RuntimeException {
    private NotEnoughDataException() {
      super(null, null, false, false);
    }
  }
  private static final NotEnoughDataException NOT_ENOUGH_DATA = new NotEnoughDataException();

  private static abstract class AbstractPart implements Part {
    final Map<String, List<String>> headers;
    final String name;
    final String filename;

    private AbstractPart(Map<String, List<String>> headers, String name, String filename) {
      this.headers = headers;
      this.name = name;
      this.filename = filename;
    }

    @Override
    public String getContentType() {
      return getHeader("content-type");
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getHeader(String name) {
      List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
      if (values != null && !values.isEmpty()) {
        return values.get(0);
      }
      return null;
    }

    @Override
    public Collection<String> getHeaders(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public Collection<String> getHeaderNames() {
      return headers.keySet();
    }
  }

  private static class ContentlessPart extends AbstractPart {
    private ContentlessPart(Map<String, List<String>> headers, String name, String filename) {
      super(headers, name, filename);
    }

    @Override
    public String getSubmittedFileName() {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public InputStream getInputStream() throws IOException {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public long getSize() {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void write(String fileName) throws IOException {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void delete() throws IOException {
      throw new UnsupportedOperationException("Not implemented");
    }

    private StreamedPart withData(String data) {
      return new DataPart(headers, name, filename, data);
    }

    private StreamedPart withPublisher(Publisher<ByteBuffer> publisher) {
      return new PartPublisher(headers, name, filename, publisher);
    }
  }

  private static class DataPart extends AbstractPart implements org.reactivestreams.servlet.http.DataPart {
    private final String data;

    private DataPart(Map<String, List<String>> headers, String name, String filename, String data) {
      super(headers, name, filename);
      this.data = data;
    }

    @Override
    public String getData() {
      return data;
    }

    @Override
    public String toString() {
      return String.format("DataPart(%s=%s)", name, data);
    }
  }

  private static class PartPublisher extends AbstractPart implements org.reactivestreams.servlet.http.PartPublisher {
    private final Publisher<ByteBuffer> publisher;

    private PartPublisher(Map<String, List<String>> headers, String name, String filename, Publisher<ByteBuffer> publisher) {
      super(headers, name, filename);
      this.publisher = publisher;
    }

    @Override
    public String getSubmittedFileName() {
      return filename;
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
      publisher.subscribe(subscriber);
    }

    @Override
    public String toString() {
      return String.format("PartPublisher(%s)", name);
    }
  }
}
