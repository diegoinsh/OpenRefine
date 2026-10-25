package com.google.refine.extension.quality.aimp;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link AimpClient} 抽取上传的字节级契约（SDK Multipart 与旧手写布局一致）。 */
public class AimpClientMultipartTest {

    @Test
    public void extractUploadBodyMatchesLegacyByteLayout() throws Exception {
        AtomicReference<String> requestContentType = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/extract/upload", exchange -> {
            requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] out = "{\"results\":{\"题名\":\"卷宗\"}}".getBytes(StandardCharsets.UTF_8);
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
            Path file = Files.createTempFile("aimp-dq-upload-", ".jpg");
            byte[] fileBytes = new byte[]{9, 8, 7};
            Files.write(file, fileBytes);

            AimpClient client = new AimpClient("http://127.0.0.1:" + server.getAddress().getPort());
            Map<String, String> result = client.extractContent(file.toString(), "题名");
            Assert.assertEquals("卷宗", result.get("题名"));

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
                    + "Content-Disposition: form-data; name=\"key_list\"\r\n\r\n题名"
                    + "\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"sync\"\r\n\r\ntrue"
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
