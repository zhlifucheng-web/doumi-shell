package com.doumiot.sshconsole.ai;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import java.util.List;
import java.util.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

@Service @SshConsoleEnabled
public class AiCompletionClient {
    static final int MAX_TOOL_CALLS = 64;

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

    static class StreamControl {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        void cancel() { cancelled=true;HttpURLConnection current=connection;if(current!=null)current.disconnect(); }
        void check() { if(cancelled)throw new CancellationException("AI request cancelled"); }
    }

    JsonNode stream(AiProviderService.Credentials provider, List<JsonNode> messages, JsonNode tools,
                    Consumer<String> onText, StreamControl control) {
        HttpURLConnection connection=null;
        try {
            control.check();
            connection=(HttpURLConnection)new URL(provider.endpoint).openConnection();control.connection=connection;
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(90000);
            connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            connection.setRequestProperty("Accept","text/event-stream, application/json");
            if(provider.apiKey!=null&&!provider.apiKey.isEmpty())connection.setRequestProperty("Authorization","Bearer "+provider.apiKey);
            ObjectNode body=AiJson.object("model",provider.model,"messages",messages,"stream",true);
            if(tools!=null&&tools.size()>0){body.set("tools",tools);body.put("tool_choice","auto");}
            control.check();
            try(OutputStream output=connection.getOutputStream()){output.write(body.toString().getBytes(StandardCharsets.UTF_8));}
            control.check();int status=connection.getResponseCode();
            if(status>=300&&status<400)throw new IllegalArgumentException("AI 地址返回重定向，请填写厂商实际的 API Base URL");
            if(status<200||status>=300)throw new IllegalArgumentException("AI 厂商返回 HTTP "+status+"，请检查地址、API Key、模型权限或额度");
            try(InputStream input=limited(connection.getInputStream(),4*1024*1024)) {
                // Some compatible providers ignore stream=true. Accept their single JSON response without retrying the request.
                if(connection.getContentType()!=null&&connection.getContentType().toLowerCase(Locale.ROOT).contains("application/json")) {
                    JsonNode result=AiJson.JSON.readTree(input),message=result.path("choices").path(0).path("message");
                    control.check();
                    if(!message.isObject()||(!message.has("content")&&!message.has("tool_calls")))throw new IllegalArgumentException("AI 响应不是兼容的 Chat Completions 格式");
                    if(message.path("content").isTextual())onText.accept(message.path("content").asText());
                    return message;
                }
                return readStream(input,onText,control);
            }
        }catch(java.net.SocketTimeoutException e){control.check();throw new IllegalArgumentException("AI 流式连接超时，已收到的内容保留，未完成的工具不会执行");}
        catch(IOException e){control.check();throw new IllegalArgumentException("AI 流式连接中断或响应格式无效，已收到的内容保留，未完成的工具不会执行");}
        finally{control.connection=null;if(connection!=null)connection.disconnect();}
    }

    private static InputStream limited(InputStream input,int limit) {
        return new FilterInputStream(input) {
            int total;
            private void count(int size)throws IOException{if(size>0&&(total+=size)>limit)throw new IOException("Response too large");}
            @Override public int read()throws IOException{int value=in.read();count(value<0?0:1);return value;}
            @Override public int read(byte[] bytes,int offset,int length)throws IOException{int size=in.read(bytes,offset,length);count(size);return size;}
        };
    }
    private JsonNode readStream(InputStream input,Consumer<String> onText,StreamControl control)throws IOException {
        StreamResult result=new StreamResult(onText);
        BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));
        String line;StringBuilder event=new StringBuilder();boolean done=false;
        while(!done&&(line=reader.readLine())!=null) {
            control.check();
            if(line.isEmpty()) {if(event.length()>0){done=result.accept(event.toString());event.setLength(0);}}
            else if(line.startsWith("data:")){if(event.length()>0)event.append('\n');event.append(line.substring(5).trim());}
        }
        if(!done&&event.length()>0)result.accept(event.toString());
        control.check();return result.finish();
    }
    private static class StreamResult {
        final StringBuilder content=new StringBuilder(),reasoning=new StringBuilder(),refusal=new StringBuilder();
        final SortedMap<Integer,ObjectNode> calls=new TreeMap<>();final Consumer<String> onText;
        String finishReason;
        StreamResult(Consumer<String> onText){this.onText=onText;}
        boolean accept(String encoded)throws IOException {
            if(encoded.equals("[DONE]"))return true;
            JsonNode packet=AiJson.JSON.readTree(encoded);
            if(packet.has("error"))throw new IllegalArgumentException("AI 流式接口返回错误，未完成的操作不会执行");
            JsonNode choice=packet.path("choices").path(0);
            if(!choice.isObject())return false;
            if(choice.hasNonNull("finish_reason"))finishReason=choice.path("finish_reason").asText();
            JsonNode delta=choice.path("delta");
            String text=delta.path("content").isTextual()?delta.path("content").asText():"";
            append(content,text,65536);if(!text.isEmpty())onText.accept(text);
            append(reasoning,delta.path("reasoning_content").asText(""),131072);
            append(refusal,delta.path("refusal").asText(""),65536);
            for(JsonNode fragment:delta.path("tool_calls")) {
                int index=toolIndex(fragment);
                ObjectNode call=calls.computeIfAbsent(index,i->AiJson.object("id","","type","function","function",AiJson.object("name","","arguments","")));
                merge(call,"id",fragment.path("id"),128);
                ObjectNode function=(ObjectNode)call.get("function");
                merge(function,"name",fragment.path("function").path("name"),128);
                merge(function,"arguments",fragment.path("function").path("arguments"),512*1024);
            }
            return false;
        }
        private int toolIndex(JsonNode fragment) {
            JsonNode value=fragment.get("index");
            if(value!=null&&!value.isNull()) {
                if(!value.isIntegralNumber()||!value.canConvertToInt())throw new IllegalArgumentException("AI 流式工具索引格式无效");
                int index=value.intValue();
                if(index<0||index>=MAX_TOOL_CALLS)throw new IllegalArgumentException("AI 单轮工具调用超过"+MAX_TOOL_CALLS+"项，索引="+index);
                return index;
            }

            String id=fragment.path("id").asText();
            if(!id.isEmpty()) {
                for(Map.Entry<Integer,ObjectNode> entry:calls.entrySet())
                    if(id.equals(entry.getValue().path("id").asText()))return entry.getKey();
                for(int index=0;index<MAX_TOOL_CALLS;index++)if(!calls.containsKey(index))return index;
            } else if(calls.size()==1) {
                return calls.firstKey();
            }
            throw new IllegalArgumentException("AI 流式工具索引缺失且无法唯一匹配工具调用");
        }
        JsonNode finish() {
            if(!Arrays.asList("stop","tool_calls","function_call").contains(finishReason))
                throw new IllegalArgumentException("length".equals(finishReason)?"AI 输出达到长度上限，内容不完整，未完成的工具不会执行":"AI 流式回答未正常结束，未完成的工具不会执行");
            ObjectNode message=AiJson.object("role","assistant","content",content.toString());
            if(reasoning.length()>0)message.put("reasoning_content",reasoning.toString());
            if(refusal.length()>0)message.put("refusal",refusal.toString());
            if(!calls.isEmpty()){ArrayNode array=AiJson.JSON.createArrayNode();calls.values().forEach(array::add);message.set("tool_calls",array);}
            return message;
        }
        static void append(StringBuilder target,String text,int limit){if(target.length()+text.length()>limit)throw new IllegalArgumentException("AI 流式返回内容过大，请缩小任务范围");target.append(text);}
        static void merge(ObjectNode target,String key,JsonNode fragment,int limit){if(fragment.isMissingNode()||fragment.isNull())return;if(!fragment.isTextual())throw new IllegalArgumentException("AI 流式工具参数格式无效");String value=target.path(key).asText()+fragment.asText();if(value.length()>limit)throw new IllegalArgumentException("AI 流式工具参数过大");target.put(key,value);}
    }
}
