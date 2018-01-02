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

import java.nio.ByteBuffer;

/**
 * A part publisher.
 *
 * This is a multipart/form-data part whose data is made available through the reactive streams
 * Publisher interface.
 */
public interface PartPublisher extends StreamedPart, Publisher<ByteBuffer> {

  /**
   * Gets the filename submitted by the client.
   *
   * If this returns null, it means the contents of the part were too large to buffer to be emitted
   * as a data part.
   *
   * @return The submitted file name, or null if non was submitted.
   */
  @Override
  String getSubmittedFileName();
}
