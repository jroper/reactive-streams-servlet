package org.reactivestreams.servlet;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.http.fileupload.FileUtils;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.file.Files;
import java.util.function.BiConsumer;

public interface WithTomcatServer extends WithVerificationServer {

  @Override
  default VerificationServer createServer() throws Exception {

    class TomcatServer implements VerificationServer {
       private final Tomcat tomcat = new Tomcat();
       private final File tempDir;

      public TomcatServer() throws Exception {
        tempDir = Files.createTempDirectory("tomcat").toFile();
      }

      @Override
      public int start(BiConsumer<HttpServletRequest, HttpServletResponse> handler) throws Exception {
        tomcat.setPort(0);
        tomcat.setBaseDir(tempDir.getAbsolutePath());

        Context ctx = tomcat.addContext("", new File(".").getAbsolutePath());

        Tomcat.addServlet(ctx, "hello", new HttpServlet() {
          protected void service(HttpServletRequest req, HttpServletResponse resp) {
            handler.accept(req, resp);
          }
        }).setAsyncSupported(true);

        ctx.addServletMappingDecoded("/*", "hello");

        tomcat.start();
        return tomcat.getConnector().getLocalPort();
      }

      @Override
      public void stop() throws Exception {
        tomcat.stop();
        FileUtils.deleteDirectory(tempDir);
      }
    }

    return new TomcatServer();
  }
}
