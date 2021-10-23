package app.unattach.model.service;

import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import com.google.api.client.util.Throwables;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class LocalServerReceiver implements VerificationCodeReceiver {
  private static final String CALLBACK_PATH = "/Callback";
  private final Lock lock;
  private final Condition gotAuthorizationResponse;
  private final String host;
  private int port;
  private Server server;
  private String code;
  private String error;

  LocalServerReceiver() {
    this("localhost", -1);
  }

  private LocalServerReceiver(String host, int port) {
    lock = new ReentrantLock();
    gotAuthorizationResponse = lock.newCondition();
    this.host = host;
    this.port = port;
  }

  @Override
  public String getRedirectUri() throws IOException {
    if (port == -1) {
      port = getUnusedPort();
    }
    server = new Server(port);

    for (Connector connector : server.getConnectors()) {
      ((ServerConnector) connector).setHost(host);
    }

    HandlerCollection handler = new HandlerCollection();
    handler.addHandler(new CallbackHandler());
    server.setHandler(handler);

    try {
      server.start();
    } catch (Exception e) {
      Throwables.propagateIfPossible(e);
      throw new IOException(e);
    }

    return "http://" + host + ":" + port + CALLBACK_PATH;
  }

  @Override
  public String waitForCode() throws IOException {
    lock.lock();

    String result;
    try {
      while (code == null && error == null) {
        gotAuthorizationResponse.awaitUninterruptibly();
      }

      if (error != null) {
        throw new IOException("User authorization failed (" + error + ").");
      }

      result = code;
    } finally {
      lock.unlock();
    }

    return result;
  }

  @Override
  public void stop() throws IOException {
    if (server != null) {
      try {
        server.stop();
      } catch (Exception e) {
        Throwables.propagateIfPossible(e);
        throw new IOException(e);
      }

      server = null;
    }
  }

  private static int getUnusedPort() throws IOException {
    try (Socket socket = new Socket()) {
      socket.bind(null);
      return socket.getLocalPort();
    }
  }

  class CallbackHandler extends AbstractHandler {
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      if (CALLBACK_PATH.equals(target)) {
        writeLandingHtml(request, response);
        response.flushBuffer();
        ((Request) request).setHandled(true);
        LocalServerReceiver.this.lock.lock();

        try {
          LocalServerReceiver.this.error = request.getParameter("error");
          LocalServerReceiver.this.code = request.getParameter("code");
          LocalServerReceiver.this.gotAuthorizationResponse.signal();
        } finally {
          LocalServerReceiver.this.lock.unlock();
        }
      }
    }

    private void writeLandingHtml(HttpServletRequest request, HttpServletResponse response) throws IOException {
      boolean success = !request.getQueryString().contains("error=access_denied");
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType("text/html");
      try (InputStream in = getClass().getResourceAsStream(success ? "/success.html" : "/failure.html")) {
        String html = IOUtils.toString(in, StandardCharsets.UTF_8);
        try (PrintWriter doc = response.getWriter()) {
          doc.print(html);
          doc.flush();
        }
      }
    }
  }
}
