package com.doumiot.sshconsole.ai;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import java.util.List;

@Service @SshConsoleEnabled
public class AiCompletionClient {
    private final RestTemplate http;
    public AiCompletionClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(10000); factory.setReadTimeout(90000);
        // Never forward the configured API key across redirects.
        http = new RestTemplate(factory) {
            @Override protected void handleResponse(java.net.URI url, HttpMethod method, org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                if (response.getStatusCode().is3xxRedirection()) throw new IllegalArgumentException("AI 地址返回重定向，请填写厂商实际的 API Base URL");
                super.handleResponse(url, method, response);
            }
        };
    }
    JsonNode complete(AiProviderService.Credentials provider, List<JsonNode> messages, JsonNode tools) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (provider.apiKey != null && !provider.apiKey.isEmpty()) headers.setBearerAuth(provider.apiKey);
        com.fasterxml.jackson.databind.node.ObjectNode body = AiJson.object("model",provider.model,"messages",messages,"stream",false);
        if (tools != null && tools.size() > 0) { body.set("tools", tools); body.put("tool_choice", "auto"); }
        try {
            String raw = http.postForObject(provider.endpoint, new HttpEntity<>(body.toString(), headers), String.class);
            if (raw == null || raw.length() > 2 * 1024 * 1024) throw new IllegalArgumentException("AI 返回为空或内容过大");
            JsonNode result = AiJson.JSON.readTree(raw);
            JsonNode message = result.path("choices").path(0).path("message");
            if (!message.isObject() || (!message.has("content") && !message.has("tool_calls"))) throw new IllegalArgumentException("AI 响应不是兼容的 Chat Completions 格式");
            return message;
        } catch (HttpStatusCodeException e) {
            int status = e.getRawStatusCode();
            String hint = status == 401 || status == 403 ? "，请检查 API Key 和模型权限" : status == 404 ? "，请检查 Base URL 和模型名称" : status == 429 ? "，请检查额度或稍后重试" : "";
            throw new IllegalArgumentException("AI 厂商返回 HTTP " + status + hint);
        } catch (ResourceAccessException e) { throw new IllegalArgumentException("部署机器无法连接 AI 厂商或请求超时，请检查地址和网络后重试"); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("AI 返回的 JSON 无法解析"); }
        catch (RestClientException e) { throw new IllegalArgumentException("AI 请求失败，请检查厂商配置"); }
    }
}
