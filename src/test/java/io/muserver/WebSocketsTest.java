package io.muserver;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.RawClient;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import javax.ws.rs.ClientErrorException;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.*;

public class WebSocketsTest {

    private MuServer server;
    private RecordingMuWebSocket serverSocket = new RecordingMuWebSocket();

    @Test
    public void handlersCanReturnNullWebSocketToHandleAsAWebSocket() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler().withWebSocketFactory(request -> {
                if (!request.relativePath().equals("/blah")) {
                    return null;
                }
                return serverSocket;
            }))
            .addHandler(Method.GET, "/not-blah", (request, response, pathParams) -> response.write("not a blah"))
            .start();

        ClientListener clientListener = new ClientListener();
        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri().resolve("/blah")), clientListener);

        MuAssert.assertNotTimedOut("Connecting", serverSocket.connectedLatch);

        clientSocket.send("This is a message");
        clientSocket.send(ByteString.encodeUtf8("This is a binary message"));
        clientSocket.send("Another text");
        clientSocket.close(1000, "Finished");
        MuAssert.assertNotTimedOut("Closing", serverSocket.closedLatch);
        assertThat(serverSocket.received, contains("connected", "onText: This is a message",
            "onBinary: This is a binary message", "onText: Another text", "onClose: 1000 Finished"));

        assertThat(clientListener.toString(), clientListener.events,
            contains("onOpen", "onMessage text: THIS IS A MESSAGE", "onMessage binary: This is a binary message", "onMessage text: ANOTHER TEXT"));

        try (Response resp = call(request(server.uri().resolve("/not-blah")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("not a blah"));
        }
    }

    @Test
    public void pathsWorkForWebsockets() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> serverSocket).withPath("/routed-websocket"))
            .start();

        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), new ClientListener());

        MuAssert.assertNotTimedOut("Connecting", serverSocket.connectedLatch);

        String largeText = StringUtils.randomStringOfLength(10000);

        clientSocket.send(largeText);
        clientSocket.send(ByteString.encodeUtf8(largeText));
        clientSocket.send("Another text");
        clientSocket.close(1000, "Finished");
        MuAssert.assertNotTimedOut("Closing", serverSocket.closedLatch);
        assertThat(serverSocket.received, contains("connected", "onText: " + largeText,
            "onBinary: " + largeText, "onText: Another text", "onClose: 1000 Finished"));
    }


    @Test
    public void asyncWritesWork() throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<String> result = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> new BaseWebSocket() {
                public void onText(String message) {
                    session().sendText("This is message one", new WriteCallback() {
                        public void onSuccess() {
                            session().sendBinary(Mutils.toByteBuffer("Async binary"), new WriteCallback() {
                                public void onSuccess() {
                                    result.complete("Success");
                                }

                                public void onFailure(Throwable reason) {
                                    result.completeExceptionally(reason);
                                }
                            });
                        }

                        public void onFailure(Throwable reason) {
                            result.completeExceptionally(reason);
                        }
                    });
                }
            }).withPath("/routed-websocket"))
            .start();

        ClientListener listener = new ClientListener();
        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), listener);
        clientSocket.send("Hey hey");
        clientSocket.close(1000, "Done");
        assertThat(result.get(10, TimeUnit.SECONDS), is("Success"));
        MuAssert.assertNotTimedOut("Client closed", listener.closedLatch);
        assertThat(listener.toString(), listener.events, contains("onOpen", "onMessage text: This is message one",
            "onMessage binary: Async binary", "onClosing 1000 Done", "onClosed 1000 Done"));
    }

    @Test
    public void ifTheFactoryThrowsAnExceptionThenItIsReturnedToTheClient() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> {
                throw new ClientErrorException(409);
            }).withPath("/409"))
            .start();
        ClientListener listener = new ClientListener();
        client.newWebSocket(webSocketRequest(server.uri().resolve("/409")), listener);
        MuAssert.assertNotTimedOut("Failure", listener.failureLatch);
        assertThat(listener.events, contains("onFailure: Expected HTTP 101 response but was '409 Conflict'"));
    }

    @Test(timeout = 30000)
    public void ifTheVersionIsNotSupportedThenA406IsReturned() throws Exception {
        server = httpServer()
            .addHandler(webSocketHandler(request -> serverSocket).withPath("/ws"))
            .start();
        RawClient rawClient = RawClient.create(server.uri())
            .sendStartLine("GET", "ws" + server.uri().resolve("/ws").toString().substring(4))
            .sendHeader("host", server.uri().getAuthority())
            .sendHeader("connection", "upgrade")
            .sendHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
            .sendHeader("Sec-WebSocket-Version", "100")
            .sendHeader("Upgrade", "websocket")
            .endHeaders()
            .flushRequest();

        while (!rawClient.responseString().contains("HTTP/1.1 426 Upgrade Required")) {
            Thread.sleep(10);
        }

        assertThat(serverSocket.received, is(empty()));
    }

    @Test
    public void sendingMessagesAfterTheClientsCloseResultInExceptions() throws InterruptedException, ExecutionException, TimeoutException, IOException {
        CompletableFuture<MuWebSocketSession> sessionFuture = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> new BaseWebSocket() {
                @Override
                public void onConnect(MuWebSocketSession session) {
                    super.onConnect(session);
                    sessionFuture.complete(session);
                }
            }).withPath("/routed-websocket"))
            .start();

        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), new ClientListener());
        MuWebSocketSession serverSession = sessionFuture.get(10, TimeUnit.SECONDS);
        clientSocket.close(1000, "Closing");

        for (int i = 0; i < 100; i++) {
            try {
                serverSession.sendText("This shouldn't work");
            } catch (IOException ignored) {
                return; // IOException, as expected. Might take a couple of attempts to get there though, hence the loop
            }
        }

        Assert.fail("No exceptions thrown");
    }


    @Test
    public void sendingMessagesAfterTheClientsCloseResultInFailureCallBacksForAsyncCalls() throws InterruptedException, ExecutionException, TimeoutException, IOException {
        CompletableFuture<MuWebSocketSession> sessionFuture = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> new BaseWebSocket() {
                @Override
                public void onConnect(MuWebSocketSession session) {
                    super.onConnect(session);
                    sessionFuture.complete(session);
                }
            }).withPath("/routed-websocket"))
            .start();

        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), new ClientListener());
        MuWebSocketSession serverSession = sessionFuture.get(10, TimeUnit.SECONDS);
        clientSocket.cancel();

        for (int i = 0; i < 100; i++) {
            CompletableFuture<String> result = new CompletableFuture<>();
            serverSession.sendText("This shouldn't work", new WriteCallback() {
                public void onSuccess() {
                    result.complete("Success");
                }
                public void onFailure(Throwable reason) {
                    result.completeExceptionally(reason);
                }
            });
            try {
                result.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                return; // as expected
            }
        }
        Assert.fail("This should have failed");
    }

    @Test
    public void pingAndPongWork() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> serverSocket).withPath("/ws"))
            .start();

        WebSocket clientSocket = client.newBuilder()
            .pingInterval(50, TimeUnit.MILLISECONDS)
            .build()
            .newWebSocket(webSocketRequest(server.uri().resolve("/ws")), new ClientListener());

        MuAssert.assertNotTimedOut("Connecting", serverSocket.connectedLatch);
        MuAssert.assertNotTimedOut("Pinging", serverSocket.pingLatch);
        clientSocket.close(1000, "Finished");
        MuAssert.assertNotTimedOut("Closing", serverSocket.closedLatch);
        assertThat(serverSocket.received, contains("connected", "onPing: ", "onClose: 1000 Finished"));
    }

    @Test
    public void theServerCanCloseSockets() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> serverSocket).withPath("/ws"))
            .start();
        ClientListener clientListener = new ClientListener();
        client.newWebSocket(webSocketRequest(server.uri().resolve("/ws")), clientListener);
        MuAssert.assertNotTimedOut("Connecting", serverSocket.connectedLatch);
        serverSocket.session.close(1001, "Umm");
        MuAssert.assertNotTimedOut("Closing", clientListener.closedLatch);
        assertThat(clientListener.toString(), clientListener.events,
            contains("onOpen", "onClosing 1001 Umm", "onClosed 1001 Umm"));
    }


    @Test
    public void ifNotMatchedThenProtocolExceptionIsReturned() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> serverSocket).withPath("/routed-websocket"))
            .start();

        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        client.newWebSocket(webSocketRequest(server.uri().resolve("/non-existant")), new WebSocketListener() {
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                failure.complete(t);
            }
        });

        Throwable actual = failure.get(10, TimeUnit.SECONDS);
        assertThat(actual, instanceOf(ProtocolException.class));
    }

    private static Request webSocketRequest(URI httpVersionOfUri) {
        return request().url("ws" + httpVersionOfUri.toString().substring(4)).build();
    }


    @After
    public void clean() {
        MuAssert.stopAndCheck(server);
    }

    private static class RecordingMuWebSocket implements MuWebSocket {
        private MuWebSocketSession session;
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch closedLatch = new CountDownLatch(1);
        CountDownLatch pingLatch = new CountDownLatch(1);

        @Override
        public void onConnect(MuWebSocketSession session) {
            this.session = session;
            received.add("connected");
            connectedLatch.countDown();
        }

        @Override
        public void onText(String message) throws IOException {
            received.add("onText: " + message);
            session.sendText(message.toUpperCase());
        }

        @Override
        public void onBinary(ByteBuffer buffer) throws IOException {
            int initial = buffer.position();
            session.sendBinary(buffer);
            buffer.position(initial);
            received.add("onBinary: " + UTF_8.decode(buffer));

        }

        @Override
        public void onClose(int statusCode, String reason) {
            received.add("onClose: " + statusCode + " " + reason);
            closedLatch.countDown();
        }

        @Override
        public void onPing(ByteBuffer payload) throws IOException {
            received.add("onPing: " + UTF_8.decode(payload));
            session.sendPong(payload);
            pingLatch.countDown();
        }

        @Override
        public void onPong(ByteBuffer payload) {
            received.add("onPong: " + UTF_8.decode(payload));
        }
    }

    private static class ClientListener extends WebSocketListener {

        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch closedLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            events.add("onOpen");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            events.add("onMessage text: " + text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            events.add("onMessage binary: " + bytes.string(UTF_8));
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            events.add("onClosing " + code + " " + reason);
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            events.add("onClosed " + code + " " + reason);
            closedLatch.countDown();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            events.add("onFailure: " + t.getMessage());
            failureLatch.countDown();
        }

        @Override
        public String toString() {
            return events.toString();
        }
    }
}