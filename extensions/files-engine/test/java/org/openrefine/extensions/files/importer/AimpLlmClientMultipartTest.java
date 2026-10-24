package org.openrefine.extensions.files.importer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * 抽取上传的**字节级契约**：SDK {@code Multipart} 产出的 body 必须与改造前手写实现逐字节一致
 * （boundary 从请求头取回，再用旧拼装算法重建期望值）。
 */
public class AimpLlmClientMultipartTest {

    @Test
    public void uploadBodyMatchesLegacyByteLayout() throws Exception {
        AtomicReference<String> requestContentType = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/extract/upload", exchange -> {
            requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] out = "{\"success\":true,\"results\":{\"题名\":\"卷宗\"},\"page_count\":2}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();

        String previousEnforce = System.getProperty("ruyi.license.enforce");
        System.setProperty("ruyi.license.enforce", "off");
        try {
            Path file = Files.createTempFile("aimp-upload-", ".jpg");
            byte[] fileBytes = new byte[]{1, 2, 3, 4, 5};
            Files.write(file, fileBytes);

            AimpLlmClient client = new AimpLlmClient(
                    "http://127.0.0.1:" + server.getAddress().getPort());
            AimpLlmClient.ExtractPageResult result = client.extractPage(
                    file.toString(), "题名,责任者", "[]", 1, 2, null, true);

            Assert.assertTrue(result.success, result.error);
            Assert.assertEquals("卷宗", result.values.get("题名"));
            Assert.assertEquals(2, result.pageCount);

            String contentType = requestContentType.get();
            Assert.assertNotNull(contentType);
            String prefix = "multipart/form-data; boundary=";
            Assert.assertTrue(contentType.startsWith(prefix), contentType);
            String boundary = contentType.substring(prefix.length());

            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getFileName() + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            expected.write(fileBytes);
            expected.write(("\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"key_list\"\r\n\r\n题名,责任者"
                    + "\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"sync\"\r\n\r\ntrue"
                    + "\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"custom_elements\"\r\n\r\n[]"
                    + "\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"options\"\r\n\r\n"
                    + "{\"current_page\":1,\"total_pages\":2,\"force_extract\":true}"
                    + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            Assert.assertEquals(requestBody.get(), expected.toByteArray());
        } finally {
            if (previousEnforce == null) {
                System.clearProperty("ruyi.license.enforce");
            } else {
                System.setProperty("ruyi.license.enforce", previousEnforce);
            }
            server.stop(0);
        }
    }
}
