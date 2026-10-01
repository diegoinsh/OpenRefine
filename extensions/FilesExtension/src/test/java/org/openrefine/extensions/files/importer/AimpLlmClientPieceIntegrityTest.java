package org.openrefine.extensions.files.importer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * 层2/层3 客户端契约：件级完整性请求的构造与解析、层3 回抽页的 force_extract 选项。
 *
 * <p>用 JDK 内置 HttpServer 做桩，把"发出去的请求体"抓下来断言——这两处都是与 AIMP 的
 * 字段级约定（`skipped` / `force_extract` / `retry_pages`），拼错字段名不会报错、
 * 只会静默退化（层3 永远不触发），故必须有回归测试兜住。
 */
public class AimpLlmClientPieceIntegrityTest {

    /** 起一个只认某个路径的桩服务，把请求体记进 captured 并返回 fixedResponse */
    private static HttpServer stub(String path, AtomicReference<String> captured, String fixedResponse)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = fixedResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return server;
    }

    private static AimpLlmClient clientFor(HttpServer server) {
        return new AimpLlmClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    public void pieceIntegritySendsPageStateAndParsesDashboard() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer server = stub("/extract/piece-integrity", captured,
                "{\"success\":true,\"dashboard\":[{\"index\":1,\"start\":1,\"end\":3,\"title\":\"\","
                        + "\"missing\":[\"title\"],\"retry_pages\":[2],\"suspect\":true}],"
                        + "\"summary\":{\"pieces\":1,\"suspect_pieces\":1,\"retry_pages\":1},"
                        + "\"reason\":\"\",\"elapsed\":0.01}");
        try {
            TitleSplitter.Piece piece = new TitleSplitter.Piece();
            piece.startPage = 1;
            piece.endPage = 3;
            piece.title = "";

            List<Map<String, String>> pageElements = Arrays.asList(
                    Collections.singletonMap("responsible_party", "张三"),
                    Collections.emptyMap(),
                    Collections.emptyMap());

            AimpLlmClient.PieceIntegrityResult r = clientFor(server).pieceIntegrity(
                    Collections.singletonList(piece), Arrays.asList(false, true, false),
                    pageElements, null, null);

            Assert.assertTrue(r.success, r.reason);
            Assert.assertEquals(1, r.dashboard.size());
            AimpLlmClient.PieceIntegrityItem item = r.dashboard.get(0);
            Assert.assertTrue(item.suspect);
            Assert.assertEquals(Arrays.asList("title"), item.missing);
            Assert.assertEquals(Arrays.asList(2), item.retryPages);
            Assert.assertEquals(1, r.retryPageCount);
            Assert.assertEquals(1, r.suspectPieces);

            // 出参解析对了，还要能证明入参也发对了：逐页状态与件区间是层2 的全部输入
            String body = captured.get();
            Assert.assertTrue(body.contains("\"skipped\":true"), body);
            Assert.assertTrue(body.contains("\"page\":2"), body);
            Assert.assertTrue(body.contains("张三"), "非空要素须随页上报：" + body);
            Assert.assertFalse(body.contains("required_elements"), "未指定必需要素时不应传：" + body);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void pieceIntegrityPassesRequiredElementsAndRetryCap() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer server = stub("/extract/piece-integrity", captured,
                "{\"success\":true,\"dashboard\":[],\"summary\":{},\"reason\":\"\"}");
        try {
            TitleSplitter.Piece piece = new TitleSplitter.Piece();
            piece.startPage = 1;
            piece.endPage = 2;
            piece.title = "民事判决书";

            clientFor(server).pieceIntegrity(Collections.singletonList(piece),
                    Arrays.asList(false, false), null,
                    Arrays.asList("title", "responsible_party"), 5);

            String body = captured.get();
            Assert.assertTrue(body.contains("required_elements"), body);
            Assert.assertTrue(body.contains("responsible_party"), body);
            Assert.assertTrue(body.contains("\"max_retry_pages\":5"), body);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void forceExtractIsSentAndPageTypeAndSkippedAreRead() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer server = stub("/extract/upload", captured,
                "{\"results\":{\"title\":{\"value\":\"民事上诉状\"}},\"data\":{\"results\":{}},"
                        + "\"processing_mode\":\"single_page\","
                        + "\"document_status\":{\"page_type\":\"header_page\"}}");
        try {
            AimpLlmClient client = clientFor(server);
            Path tmp = Files.createTempFile("aimp-page", ".jpg");
            Files.write(tmp, new byte[] { 1, 2, 3 });
            try {
                AimpLlmClient.ExtractPageResult forced = client.extractPage(
                        tmp.toString(), "title", null, 2, 5, null, true);
                Assert.assertTrue(forced.success, forced.error);
                Assert.assertTrue(captured.get().contains("\"force_extract\":true"),
                        "层3 回抽必须带 force_extract：" + captured.get());
                Assert.assertEquals("header_page", forced.pageType);

                AimpLlmClient.ExtractPageResult plain = client.extractPage(
                        tmp.toString(), "title", null, 2, 5, null, false);
                Assert.assertTrue(plain.success, plain.error);
                Assert.assertFalse(captured.get().contains("force_extract"),
                        "常规逐页抽取不应带 force_extract：" + captured.get());
            } finally {
                Files.deleteIfExists(tmp);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void syncPathRecognizesSkippedPage() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer server = stub("/extract/upload", captured,
                "{\"results\":{},\"data\":{\"results\":{}},"
                        + "\"processing_mode\":\"single_page_skipped_by_classifier\"}");
        try {
            Path tmp = Files.createTempFile("aimp-page-skip", ".jpg");
            Files.write(tmp, new byte[] { 4, 5, 6 });
            try {
                AimpLlmClient.ExtractPageResult r = clientFor(server).extractPage(
                        tmp.toString(), "title", null, 1, 3, null, false);
                // 图片模式走同步路径：短路页必须能被认出来，否则它会被算进「平均每页耗时」的分母
                Assert.assertTrue(r.skipped, "同步路径须识别 single_page_skipped*");
            } finally {
                Files.deleteIfExists(tmp);
            }
        } finally {
            server.stop(0);
        }
    }
}
