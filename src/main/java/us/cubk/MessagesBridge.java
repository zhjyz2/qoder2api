package us.cubk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public final class MessagesBridge {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final BearerBuilder.SessionContext sess;
    private final BearerApiClient bearerClient;
    private final JsonNode templateBase;

    public MessagesBridge(String pat) throws Exception {
        String mid = UUID.randomUUID().toString();
        String mtoken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((UUID.randomUUID().toString() + UUID.randomUUID()).substring(0, 50).getBytes());
        String mtype = UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        var sigClient = new SignatureApiClient(mid, mtoken, mtype);
        JsonNode jt = sigClient.exchangeJobToken(pat);
        System.out.println("[claude-bridge] session for " + jt.path("name").asText() + " (" + jt.path("id").asText() + ")");
        var identity = new BearerBuilder.AuthIdentity(jt.path("name").asText(""), jt.path("id").asText(""), jt.path("id").asText(""), "", "", "", jt.path("userType").asText("personal_standard"), jt.path("securityOauthToken").asText(), jt.path("refreshToken").asText());
        this.sess = BearerBuilder.newSession(identity, mid, mtoken, mtype);
        this.bearerClient = new BearerApiClient(sess);
        String basePrompt = new String(java.nio.file.Files.readAllBytes(new File("baseprompt.json").toPath()));
        basePrompt = basePrompt.replace("{UUID1}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID2}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID3}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID4}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{UUID5}", UUID.randomUUID().toString());
        basePrompt = basePrompt.replace("{TIME1}", String.valueOf(System.currentTimeMillis()));
        this.templateBase = objectMapper.readTree(basePrompt);
    }

    public void start(int port) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/v1/messages", this::handleMessages);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("[claude-bridge] listening http://127.0.0.1:" + port + "/v1/messages");
    }

    private void handleMessages(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
//            try {
//                JsonNode requestJson = objectMapper.readTree(ex.getRequestBody().readAllBytes());
//                String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestJson);
//                java.nio.file.Files.writeString(
//                        java.nio.file.Paths.get("request.json"),
//                        prettyJson,
//                        java.nio.charset.StandardCharsets.UTF_8,
//                        java.nio.file.StandardOpenOption.CREATE,
//                        java.nio.file.StandardOpenOption.APPEND
//                );
//            } catch (Exception e) {
//                System.err.println("[claude-bridge] Failed to write request log: " + e.getMessage());
//            }
            JsonNode req = objectMapper.readTree(ex.getRequestBody());
            boolean stream = req.path("stream").asBoolean(false);
            String model = req.path("model").asText("lite");
            JsonNode messages = req.path("messages");
            String systemPrompt = req.path("system").asText("");
            int maxTokens = req.path("max_tokens").asInt(32768);

            // Build upstream request body
            ObjectNode body = templateBase.deepCopy();
            String nid = UUID.randomUUID().toString();
            body.put("request_id", nid);
            body.put("chat_record_id", nid);
            body.put("request_set_id", UUID.randomUUID().toString());
            body.put("session_id", UUID.randomUUID().toString());
            body.put("stream", true);
            body.put("aliyun_user_type", sess.identity().userType());
            ObjectNode mc = (ObjectNode) body.path("model_config");
            mc.put("key", model);
            ObjectNode biz = (ObjectNode) body.path("business");
            biz.put("id", UUID.randomUUID().toString());
            biz.put("begin_at", System.currentTimeMillis());

            // Apply max_tokens from request to upstream
            ObjectNode params = (ObjectNode) body.path("parameters");
            params.put("max_tokens", maxTokens);

            // Extract last user message as prompt
            String prompt = "";
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode m = messages.get(i);
                if ("user".equals(m.path("role").asText())) {
                    JsonNode c = m.path("content");
                    if (c.isTextual()) {
                        prompt = c.asText();
                    } else if (c.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode block : c) {
                            if ("text".equals(block.path("type").asText())) {
                                sb.append(block.path("text").asText());
                            }
                        }
                        prompt = sb.toString();
                    } else {
                        prompt = c.toString();
                    }
                    break;
                }
            }

            // If system prompt exists, prepend it
            if (!systemPrompt.isEmpty()) {
                prompt = systemPrompt + "\n\n" + prompt;
            }

            ObjectNode ctx = (ObjectNode) body.path("chat_context");
            ((ObjectNode) ctx.path("text")).put("text", prompt);
            ((ObjectNode) ctx.path("extra").path("originalContent")).put("text", prompt);
            biz.put("name", prompt.length() > 30 ? prompt.substring(0, 30) : prompt);

            // Rebuild messages array
            ArrayNode msgsArr = (ArrayNode) body.path("messages");
            ArrayNode rebuilt = objectMapper.createArrayNode();
            for (JsonNode msg : msgsArr) {
                if (!"user".equals(msg.path("role").asText())) {
                    rebuilt.add(msg);
                }
            }
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", "");
            ArrayNode contents = objectMapper.createArrayNode();
            ObjectNode cn = objectMapper.createObjectNode();
            cn.put("type", "text");
            cn.put("text", prompt);
            contents.add(cn);
            userMsg.set("contents", contents);
            ObjectNode rmu = objectMapper.createObjectNode();
            rmu.put("prompt_tokens", 0);
            rmu.put("completion_tokens", 0);
            rmu.put("total_tokens", 0);
            ObjectNode ctd = objectMapper.createObjectNode();
            ctd.put("reasoning_tokens", 0);
            rmu.set("completion_tokens_details", ctd);
            ObjectNode ptd = objectMapper.createObjectNode();
            ptd.put("cached_tokens", 0);
            rmu.set("prompt_tokens_details", ptd);
            ObjectNode rm = objectMapper.createObjectNode();
            rm.put("id", "");
            rm.set("usage", rmu);
            userMsg.set("response_meta", rm);
            userMsg.put("reasoning_content_signature", "");
            rebuilt.add(userMsg);
            body.set("messages", rebuilt);

            System.out.println("[claude-bridge] prompt=" + (prompt.length() > 80 ? prompt.substring(0, 80) + "..." : prompt));

            String url = "https://api3.qoder.sh/algo/api/v2/service/pro/sse/agent_chat_generation" + "?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1";
            Map<String, String> extraHeaders = Map.of("x-model-key", model, "x-model-source", mc.path("source").asText("system"));

            String msgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            long created = System.currentTimeMillis() / 1000;

            if (stream) {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.getResponseHeaders().add("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, 0);
                OutputStream out = ex.getResponseBody();

                // message_start event
                ObjectNode msgStart = objectMapper.createObjectNode();
                msgStart.put("type", "message_start");
                ObjectNode message = objectMapper.createObjectNode();
                message.put("id", msgId);
                message.put("type", "message");
                message.put("role", "assistant");
                message.set("content", objectMapper.createArrayNode());
                message.put("model", model);
                message.putNull("stop_reason");
                message.putNull("stop_sequence");
                ObjectNode usage = objectMapper.createObjectNode();
                usage.put("input_tokens", 0);
                usage.put("output_tokens", 0);
                message.set("usage", usage);
                msgStart.set("message", message);
                out.write(("event: message_start\ndata: " + objectMapper.writeValueAsString(msgStart) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();

                // content_block_start
                ObjectNode blockStart = objectMapper.createObjectNode();
                blockStart.put("type", "content_block_start");
                blockStart.put("index", 0);
                ObjectNode contentBlock = objectMapper.createObjectNode();
                contentBlock.put("type", "text");
                contentBlock.put("text", "");
                blockStart.set("content_block", contentBlock);
                out.write(("event: content_block_start\ndata: " + objectMapper.writeValueAsString(blockStart) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();

                // ping
                out.write(("event: ping\ndata: {\"type\":\"ping\"}\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();

                // Stream content deltas
                bearerClient.openStreamLines(url, body, extraHeaders, line -> {
                    if (!line.startsWith("data:")) return;
                    String content = extractContent(line.substring(5).trim());
                    if (content == null || content.isEmpty()) return;
                    try {
                        ObjectNode delta = objectMapper.createObjectNode();
                        delta.put("type", "content_block_delta");
                        delta.put("index", 0);
                        ObjectNode deltaContent = objectMapper.createObjectNode();
                        deltaContent.put("type", "text_delta");
                        deltaContent.put("text", content);
                        delta.set("delta", deltaContent);
                        out.write(("event: content_block_delta\ndata: " + objectMapper.writeValueAsString(delta) + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (IOException ie) {
                        throw new RuntimeException(ie);
                    }
                });

                // content_block_stop
                ObjectNode blockStop = objectMapper.createObjectNode();
                blockStop.put("type", "content_block_stop");
                blockStop.put("index", 0);
                out.write(("event: content_block_stop\ndata: " + objectMapper.writeValueAsString(blockStop) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();

                // message_delta
                ObjectNode msgDelta = objectMapper.createObjectNode();
                msgDelta.put("type", "message_delta");
                ObjectNode deltaObj = objectMapper.createObjectNode();
                deltaObj.put("stop_reason", "end_turn");
                deltaObj.putNull("stop_sequence");
                msgDelta.set("delta", deltaObj);
                ObjectNode deltaUsage = objectMapper.createObjectNode();
                deltaUsage.put("output_tokens", 0);
                msgDelta.set("usage", deltaUsage);
                out.write(("event: message_delta\ndata: " + objectMapper.writeValueAsString(msgDelta) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();

                // message_stop
                out.write(("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n").getBytes(StandardCharsets.UTF_8));
                out.close();
            } else {
                StringBuilder full = new StringBuilder();
                bearerClient.openStreamLines(url, body, extraHeaders, line -> {
                    if (!line.startsWith("data:")) return;
                    String content = extractContent(line.substring(5).trim());
                    if (content != null) full.append(content);
                });
                ObjectNode resp = objectMapper.createObjectNode();
                resp.put("id", msgId);
                resp.put("type", "message");
                resp.put("role", "assistant");
                ArrayNode contentArr = objectMapper.createArrayNode();
                ObjectNode textBlock = objectMapper.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", full.toString());
                contentArr.add(textBlock);
                resp.set("content", contentArr);
                resp.put("model", model);
                resp.put("stop_reason", "end_turn");
                resp.putNull("stop_sequence");
                ObjectNode usageResp = objectMapper.createObjectNode();
                usageResp.put("input_tokens", 0);
                usageResp.put("output_tokens", 0);
                resp.set("usage", usageResp);
                byte[] outBytes = objectMapper.writeValueAsBytes(resp);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, outBytes.length);
                ex.getResponseBody().write(outBytes);
            }
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "unknown error";
            String err = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"" + errMsg + "\"}}";
            byte[] errBytes = err.getBytes(StandardCharsets.UTF_8);
            try {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(500, errBytes.length);
                ex.getResponseBody().write(errBytes);
            } catch (IOException ignore) {
            }
        } finally {
            ex.close();
        }
    }

    private String extractContent(String dataLine) {
        try {
            JsonNode wrapper = objectMapper.readTree(dataLine);
            String inner = wrapper.path("body").asText("");
            if (inner.isEmpty()) return null;
            JsonNode innerJson = objectMapper.readTree(inner);
            for (JsonNode ch : innerJson.path("choices")) {
                JsonNode delta = ch.path("delta");
                if (delta.has("content") && !delta.path("content").asText().isEmpty()) {
                    return delta.path("content").asText();
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    public static void run(String pat, int port) throws Exception {
        if (pat == null || pat.isBlank()) {
            pat = System.getenv("QODER_PAT");
            if (pat == null || pat.isBlank()) throw new RuntimeException("Token required!");
        }
        new MessagesBridge(pat).start(port);
        Thread.currentThread().join();
    }

    public static void main(String[] args) throws Exception {
        run(null, 8964);
    }
}
