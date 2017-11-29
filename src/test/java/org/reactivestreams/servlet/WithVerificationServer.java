package org.reactivestreams.servlet;

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
