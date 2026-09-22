package com.example.Ece.agent.rag;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实 HTTP 打到本机临时端口，验证 {@link HttpEmbeddingClient} 的批量契约与失败语义。
 *
 * <p>不 mock RestTemplate：这里要验证的恰恰是"字节流进出之后"的东西——JSON 解析、条数校验。
 * 用一个返回固定响应的 {@link HttpServer}（JDK 自带）即可。</p>
 */
class HttpEmbeddingClientTest {

    private HttpServer server;
    private String baseUrl;
    /** 服务端收到的请求体，供断言"客户端确实一次发了多条"。 */
    private final AtomicReference<String> lastRequestBody = new AtomicReference<String>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respondWith(final String body) {
        server.createContext("/embed", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                requestCount.incrementAndGet();
                lastRequestBody.set(readAll(exchange.getRequestBody()));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream out = exchange.getResponseBody();
                out.write(bytes);
                out.close();
            }
        });
        server.start();
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 一次请求返回 3 条向量，且请求体里确实带了 3 个文本。 */
    @Test
    void sendsOneRequestForTheWholeBatchAndParsesEveryVector() throws Exception {
        respondWith("{\"vectors\":[[1.0,0.0,0.5],[0.0,1.0,0.25],[0.5,0.5,1.0]]}");
        HttpEmbeddingClient client = new HttpEmbeddingClient(baseUrl, 3000);

        List<double[]> vectors = client.embedBatch(Arrays.asList("番茄早疫病", "番茄晚疫病", "玉米锈病"));

        assertEquals(3, vectors.size(), "返回条数必须与请求条数一致");
        assertEquals(3, vectors.get(0).length);
        assertEquals(0.25, vectors.get(1)[2], 1e-9);
        assertEquals(1, requestCount.get(), "整批只应产生一次 HTTP 往返");
        String body = lastRequestBody.get();
        for (String text : new String[]{"番茄早疫病", "番茄晚疫病", "玉米锈病"}) {
            assertTrue(body.contains(text), "请求体应含文本：" + text + "，实际：" + body);
        }
    }

    /**
     * 服务端返回条数少于请求条数时必须整批判失败。
     *
     * <p>这是**正确性守卫**而不是洁癖：按错位使用会把 A 块的向量写到 B 块上，
     * 检索期表现为"偶发答非所问"，几乎无法定位。宁可这批全不要。</p>
     */
    @Test
    void rejectsBatchWhenServerReturnsFewerVectorsThanRequested() {
        respondWith("{\"vectors\":[[1.0,0.0]]}");
        HttpEmbeddingClient client = new HttpEmbeddingClient(baseUrl, 3000);

        EmbeddingUnavailableException error = assertThrows(EmbeddingUnavailableException.class,
                () -> client.embedBatch(Arrays.asList("番茄早疫病", "番茄晚疫病")));
        assertTrue(error.getMessage().contains("mismatch"), "异常信息应点明条数不符：" + error.getMessage());
    }

    @Test
    void rejectsResponseWithoutVectors() {
        respondWith("{\"vectors\":[]}");
        HttpEmbeddingClient client = new HttpEmbeddingClient(baseUrl, 3000);

        assertThrows(EmbeddingUnavailableException.class, () -> client.embedBatch(Arrays.asList("番茄早疫病")));
    }

    /** 单条接口取第一条向量，与批量走同一条解析路径。 */
    @Test
    void singleEmbedReturnsFirstVector() throws Exception {
        respondWith("{\"vectors\":[[0.125,0.875]]}");
        HttpEmbeddingClient client = new HttpEmbeddingClient(baseUrl, 3000);

        double[] vector = client.embed("番茄");

        assertEquals(2, vector.length);
        assertEquals(0.125, vector[0], 1e-9);
        assertEquals(1, requestCount.get());
    }

    /** 空入参不该发请求（避免把一次无意义的往返算成一次失败重试）。 */
    @Test
    void emptyBatchDoesNotHitTheServer() throws Exception {
        respondWith("{\"vectors\":[[1.0]]}");
        HttpEmbeddingClient client = new HttpEmbeddingClient(baseUrl, 3000);

        assertEquals(0, client.embedBatch(new ArrayList<String>()).size());
        assertEquals(0, requestCount.get());
    }
}
