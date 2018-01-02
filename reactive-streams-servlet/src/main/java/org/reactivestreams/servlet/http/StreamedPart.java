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

import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;

/**
 * Streamed part, published from the multipart parser.
 *
 * Streamed parts are either a {@link DataPart} or a {@link PartPublisher}. A data part is any
 * part that doesn't have a file name and is below the buffering threshold. Anything else is
 * a part publisher.
 *
 * Streamed parts do not support the blocking operations that javax.servlet.http.Part supports.
 */
public interface StreamedPart extends Part {

  @Override
  default InputStream getInputStream() throws IOException {
    throw new UnsupportedOperationException("Streamed parts do not support getInputStream.");
  }

  @Override
  default void write(String fileName) throws IOException {
    throw new UnsupportedOperationException("Streamed parts do not support write.");
  }

  @Override
  default void delete() throws IOException {
    throw new UnsupportedOperationException("Streamed parts do not support delete.");
  }

  @Override
  default long getSize() {
    throw new UnsupportedOperationException("Streamed parts do not support getSize.");
  }
}
