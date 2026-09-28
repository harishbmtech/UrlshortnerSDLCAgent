package com.example.sdlc.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny stand-in for an Ollama server, on a random local port. It speaks just enough of the Ollama REST API
 * for tests: {@code GET /api/tags} (the pulled models) and non-streaming {@code POST /api/chat}.
 * Every chat request's {@code model} is recorded, so tests can check which model served each agent.
 */
public final class FakeOllamaServer implements AutoCloseable {

    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpServer server;
    private final List<String> chatModels = new CopyOnWriteArrayList<>();
    private volatile List<String> installed;
    private volatile Function<String, String> chatContent = body -> "{}";

    public FakeOllamaServer(List<String> installedModels) {
        this.installed = List.copyOf(installedModels);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/tags", this::tags);
        server.createContext("/api/chat", this::chat);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setInstalled(List<String> models) {
        this.installed = List.copyOf(models);
    }

    /** What the assistant replies, given the raw request body. */
    public void onChat(Function<String, String> content) {
        this.chatContent = content;
    }

    public List<String> chatModels() {
        return chatModels;
    }

    private void tags(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("{\"models\":[");
        for (int i = 0; i < installed.size(); i++) {
            String m = installed.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(m).append("\",\"model\":\"").append(m)
                    .append("\",\"size\":4661224676,\"details\":{\"format\":\"gguf\",\"family\":\"llama\"}}");
        }
        sb.append("]}");
        respond(ex, sb.toString());
    }

    private void chat(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = MODEL.matcher(body);
        String model = m.find() ? m.group(1) : "?";
        chatModels.add(model);
        String content = chatContent.apply(body);
        respond(ex, "{\"model\":\"" + model + "\",\"created_at\":\"2026-09-27T12:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":" + quote(content) + "},"
                + "\"done_reason\":\"stop\",\"done\":true,\"total_duration\":1000,\"prompt_eval_count\":10,\"eval_count\":10}");
    }

    private static void respond(HttpExchange ex, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
