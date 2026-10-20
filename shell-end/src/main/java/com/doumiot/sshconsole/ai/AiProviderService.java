package com.doumiot.sshconsole.ai;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.net.URI;
import java.util.*;

@Service @SshConsoleEnabled
public class AiProviderService {
    @Resource private JdbcTemplate jdbc;
    @Resource private CredentialCipher cipher;

    @Getter @Setter public static class Settings {
        private String id, name, baseUrl, model, apiKey;
        private boolean clearApiKey;
    }
    static class Credentials {
        String id, name, endpoint, model, apiKey;
    }
    @PostConstruct void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS ai_provider (id TEXT PRIMARY KEY, name TEXT NOT NULL, base_url TEXT NOT NULL, model TEXT NOT NULL, api_key_enc BLOB)");
    }
    public List<Map<String,Object>> list() {
        return jdbc.query("SELECT id,name,base_url,model,api_key_enc FROM ai_provider ORDER BY rowid", (row, index) -> {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("id", row.getString(1)); result.put("name", row.getString(2));
            result.put("baseUrl", row.getString(3)); result.put("model", row.getString(4));
            result.put("hasApiKey", row.getBytes(5) != null); return result;
        });
    }
    public synchronized Object save(Settings settings) {
        if (settings == null) throw new IllegalArgumentException("请填写 AI 厂商配置");
        String name = required(settings.name, 100), model = required(settings.model, 200);
        String base = normalizeBaseUrl(settings.baseUrl);
        boolean create = settings.id == null || settings.id.isEmpty();
        String id = create ? UUID.randomUUID().toString() : settings.id;
        if (!create) require(id);
        byte[] secret = create ? null : jdbc.queryForObject("SELECT api_key_enc FROM ai_provider WHERE id=?", byte[].class, id);
        if (settings.clearApiKey) secret = null;
        if (settings.apiKey != null && !settings.apiKey.isEmpty()) {
            if (settings.apiKey.length() > 8192 || settings.apiKey.contains("\r") || settings.apiKey.contains("\n")) throw new IllegalArgumentException("API Key 格式无效");
            secret = cipher.encryptSecretJson(AiJson.object("apiKey", settings.apiKey).toString());
        }
        if (create) jdbc.update("INSERT INTO ai_provider(id,name,base_url,model,api_key_enc) VALUES (?,?,?,?,?)",id,name,base,model,secret);
        else jdbc.update("UPDATE ai_provider SET name=?,base_url=?,model=?,api_key_enc=? WHERE id=?",name,base,model,secret,id);
        return AiJson.object("id", id);
    }
    public synchronized void delete(String id) { require(id); jdbc.update("DELETE FROM ai_provider WHERE id=?", id); }
    Credentials credentials(String id) {
        require(id);
        return jdbc.queryForObject("SELECT name,base_url,model,api_key_enc FROM ai_provider WHERE id=?", (row,index) -> {
            Credentials result = new Credentials(); result.id = id; result.name = row.getString(1);
            result.endpoint = endpoint(row.getString(2)); result.model = row.getString(3);
            byte[] secret = row.getBytes(4);
            try { result.apiKey = secret == null ? "" : AiJson.JSON.readTree(cipher.decryptToJson(secret)).path("apiKey").asText(); }
            catch (Exception e) { throw new IllegalArgumentException("AI API Key 无法解密，请重新保存配置"); }
            return result;
        }, id);
    }
    private void require(String id) {
        if (id == null || jdbc.queryForObject("SELECT COUNT(*) FROM ai_provider WHERE id=?", Integer.class, id) != 1)
            throw new IllegalArgumentException("AI 厂商配置不存在，请先保存配置");
    }
    static String normalizeBaseUrl(String input) {
        String value = required(input, 2048).replaceAll("/+$", "");
        try {
            URI uri = URI.create(value);
            if (!Arrays.asList("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
        } catch (Exception e) { throw new IllegalArgumentException("Base URL 须为有效的 HTTP/HTTPS 地址，不包含密钥、查询参数或片段"); }
        return value;
    }
    static String endpoint(String base) { return base.endsWith("/chat/completions") ? base : base + "/chat/completions"; }
    private static String required(String value, int max) {
        if (value == null || value.trim().isEmpty() || value.length() > max) throw new IllegalArgumentException("厂商名称、Base URL 和模型均须填写且不能超长");
        return value.trim();
    }
}
