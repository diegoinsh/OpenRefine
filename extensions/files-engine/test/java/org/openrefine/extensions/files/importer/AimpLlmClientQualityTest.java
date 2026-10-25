package org.openrefine.extensions.files.importer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * AimpLlmClient 对抽取质量字段（quality_score / quality_flags）的解析契约：
 * 单页同步响应、多页页级明细、旧响应（无质量字段）、非法值四种情形都要「只增字段、不改值、
 * 不抛异常」。对应设计与实施规格 §9.2。
 */
public class AimpLlmClientQualityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AimpLlmClient.ExtractPageResult parseTaskResult(String json) throws Exception {
        AimpLlmClient client = new AimpLlmClient("http://127.0.0.1:1");
        JsonNode node = MAPPER.readTree(json);
        return client.parseTaskResult(node);
    }

    /** 多页任务结果顶层 results 保留胜出值的质量字段。 */
    @Test
    public void topLevelResultsCarryQuality() throws Exception {
        AimpLlmClient.ExtractPageResult r = parseTaskResult(
                "{\"results\":{"
                        + "\"题名\":{\"value\":\"民事判决书\",\"confidence\":0.9,"
                        + "\"quality_score\":0.62,\"quality_flags\":[\"title_not_in_head\"]},"
                        + "\"责任者\":{\"value\":\"某法院\",\"confidence\":0.88,\"quality_score\":0.95}}}");

        Assert.assertTrue(r.success, r.error);
        Assert.assertEquals(r.values.get("题名"), "民事判决书");
        Assert.assertEquals(r.qualityScores.get("题名").doubleValue(), 0.62, 1e-9);
        Assert.assertEquals(r.qualityFlags.get("题名"), List.of("title_not_in_head"));
        Assert.assertEquals(r.qualityScores.get("责任者").doubleValue(), 0.95, 1e-9);
        Assert.assertNull(r.qualityFlags.get("责任者"), "空/缺失 flags 不应写入");
    }

    /** 页级明细（page_extractions）的每个候选携带该页的质量。 */
    @Test
    public void pageExtractionsCarryQuality() throws Exception {
        AimpLlmClient.ExtractPageResult r = parseTaskResult(
                "{\"results\":{\"责任者\":{\"value\":\"某法院\",\"confidence\":0.9}},"
                        + "\"page_extractions\":[{\"page_index\":2,\"elements\":{"
                        + "\"责任者\":{\"value\":\"某法院\",\"confidence\":0.9,"
                        + "\"quality_score\":0.3,\"quality_flags\":[\"signature_label\"]}}}]}");

        List<AimpLlmClient.ElementCandidate> candidates = r.candidates.get("责任者");
        Assert.assertNotNull(candidates);
        AimpLlmClient.ElementCandidate c = candidates.get(0);
        Assert.assertEquals(c.page, 2);
        Assert.assertEquals(c.value, "某法院");
        Assert.assertEquals(c.qualityScore.doubleValue(), 0.3, 1e-9);
        Assert.assertEquals(c.qualityFlags, List.of("signature_label"));
        // 胜出标记由 Java 写行时决定，解析阶段一律不标
        Assert.assertNull(c.winner);
    }

    /** 旧 AIMP（不返回质量字段）解析后按「未评分」处理，不报错。 */
    @Test
    public void legacyResultWithoutQualityParsesAsUnscored() throws Exception {
        AimpLlmClient.ExtractPageResult r = parseTaskResult(
                "{\"results\":{\"题名\":{\"value\":\"送达回证\",\"confidence\":0.72}},"
                        + "\"page_extractions\":[{\"page_index\":1,\"elements\":{"
                        + "\"题名\":{\"value\":\"送达回证\",\"confidence\":0.72}}}]}");

        Assert.assertTrue(r.success, r.error);
        Assert.assertTrue(r.qualityScores.isEmpty());
        Assert.assertTrue(r.qualityFlags.isEmpty());
        AimpLlmClient.ElementCandidate c = r.candidates.get("题名").get(0);
        Assert.assertNull(c.qualityScore);
        Assert.assertNull(c.qualityFlags);
    }

    /** 非法质量值（非数值字符串、非数组 flags、空数组）一律忽略，不影响其余字段解析。 */
    @Test
    public void invalidQualityValuesAreIgnoredNotFatal() throws Exception {
        AimpLlmClient.ExtractPageResult r = parseTaskResult(
                "{\"results\":{"
                        + "\"题名\":{\"value\":\"X\",\"quality_score\":\"abc\","
                        + "\"quality_flags\":\"title_not_in_head\"},"
                        + "\"责任者\":{\"value\":\"Y\",\"quality_score\":\"0.8\",\"quality_flags\":[]}}}");

        Assert.assertTrue(r.success, r.error);
        Assert.assertNull(r.qualityScores.get("题名"), "非数值字符串按未评分");
        Assert.assertNull(r.qualityFlags.get("题名"), "非数组按无标记");
        Assert.assertEquals(r.qualityScores.get("责任者").doubleValue(), 0.8, 1e-9,
                "数值字符串仍可解析为分数");
        Assert.assertNull(r.qualityFlags.get("责任者"), "空数组视为无标记");
        Assert.assertEquals(r.values.get("题名"), "X");
        Assert.assertEquals(r.values.get("责任者"), "Y");
    }

    /** 单页同步响应：值取自顶层纯值 results，质量取自 data.results。 */
    @Test
    public void syncUploadReadsQualityFromDataResults() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/extract/upload", exchange -> {
            byte[] out = ("{\"success\":true,\"results\":{\"题名\":\"民事判决书\"},"
                    + "\"data\":{\"results\":{"
                    + "\"题名\":{\"value\":\"民事判决书\",\"confidence\":0.9,"
                    + "\"quality_score\":0.62,\"quality_flags\":[\"title_not_in_head\"]}}},"
                    + "\"page_count\":1}").getBytes(StandardCharsets.UTF_8);
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
            Path file = Files.createTempFile("aimp-quality-", ".jpg");
            Files.write(file, new byte[]{1, 2, 3});

            AimpLlmClient client = new AimpLlmClient(
                    "http://127.0.0.1:" + server.getAddress().getPort());
            AimpLlmClient.ExtractPageResult r = client.extractPage(
                    file.toString(), "题名", "[]", 1, 1, null, true);

            Assert.assertTrue(r.success, r.error);
            Assert.assertEquals(r.values.get("题名"), "民事判决书");
            Assert.assertEquals(r.qualityScores.get("题名").doubleValue(), 0.62, 1e-9);
            Assert.assertEquals(r.qualityFlags.get("题名"), List.of("title_not_in_head"));
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
