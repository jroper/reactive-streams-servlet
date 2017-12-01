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
package org.reactivestreams.servlet.test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.function.BiConsumer;

/**
 * Trait to allow mixing in a factory for verification servers.
 */
public interface WithVerificationServer {

  VerificationServer createServer() throws Exception;

  /**
   * Abstraction over servlet implementations to be verified.
   */
  interface VerificationServer {

    /**
     * Start the server.
     *
     * @param handler The handler to handle requests.
     * @return The port the server is running on.
     */
    int start(BiConsumer<HttpServletRequest, HttpServletResponse> handler) throws Exception;

    /**
     * Stop the server.
     */
    void stop() throws Exception;
  }
}
