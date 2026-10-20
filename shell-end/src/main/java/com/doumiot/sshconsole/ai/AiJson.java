package com.doumiot.sshconsole.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

final class AiJson {
    static final ObjectMapper JSON = new ObjectMapper();
    private AiJson() { }
    static ObjectNode object(Object... pairs) {
        ObjectNode node = JSON.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) node.set((String) pairs[i], JSON.valueToTree(pairs[i + 1]));
        return node;
    }
    static JsonNode mask(JsonNode value) {
        if (value == null) return JSON.nullNode();
        if (value.isObject()) {
            ObjectNode copy = JSON.createObjectNode();
            value.fields().forEachRemaining(e -> {
                String key = e.getKey().toLowerCase(Locale.ROOT);
                boolean secret = key.matches(".*(password|passphrase|apikey|api_key|privatekey|private_key|secretenc|ciphertext).*" ) || key.equals("secret");
                copy.set(e.getKey(), secret && !e.getValue().isNull() ? JSON.getNodeFactory().textNode("••••••") : mask(e.getValue()));
            });
            return copy;
        }
        if (value.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode copy = JSON.createArrayNode();
            value.forEach(v -> copy.add(mask(v))); return copy;
        }
        return value.deepCopy();
    }
    static String text(JsonNode args, String key) {
        JsonNode value = args.get(key);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) throw new IllegalArgumentException(key + " 必填");
        return value.asText();
    }
    static long id(JsonNode args, String key) {
        JsonNode value = args.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1)
            throw new IllegalArgumentException(key + " 须为有效 ID");
        return value.longValue();
    }
    static String bounded(String text, int max) { return text.length() > max ? text.substring(0, max) + "\n[内容已截断]" : text; }
}
