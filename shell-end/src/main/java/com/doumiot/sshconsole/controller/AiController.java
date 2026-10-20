package com.doumiot.sshconsole.controller;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.result.NoWrap;
import com.doumiot.sshconsole.ai.*;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

@RestController @SshConsoleEnabled
@RequestMapping("/api/ssh-console/ai")
public class AiController {
    @Resource private AiProviderService providers;
    @Resource private AiChatService chats;
    @Resource private AiTools tools;
    @Resource private AiFiles files;
    @Resource private AiEventStream streams;
    @Resource private ObjectMapper json;

    @ModelAttribute void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @ExceptionHandler(IllegalArgumentException.class) public Object invalid(IllegalArgumentException e){return com.doumiot.spring.result.Result.failure(ParamExceptionEnum.IN_PARAM_EMPTY,e.getCause()==null?e.getMessage():"AI 请求参数格式无效");}
    @GetMapping("/providers") public Object providers(){return providers.list();}
    @PostMapping("/providers") public Object save(@RequestBody AiProviderService.Settings request){return providers.save(request);}
    @DeleteMapping("/providers/{id}") public void deleteProvider(@PathVariable String id){providers.delete(id);}
    @PostMapping("/providers/{id}/test") public Object test(@PathVariable String id){return chats.test(id);}
    @GetMapping("/tools") public Object tools(){return tools.catalog();}
    @PostMapping("/tools/call") public Object call(@RequestBody JsonNode body) throws Exception {
        String owner=body.path("conversationId").asText("api");if(!owner.equals("api"))chats.requireOwner(owner);
        return tools.call(owner,body.path("name").asText(),body.get("arguments"));
    }
    @PostMapping("/tools/confirm") public Object confirm(@RequestBody JsonNode body) throws Exception {
        String owner=body.path("conversationId").asText("api");if(!owner.equals("api"))chats.requireOwner(owner);
        if(!body.path("approved").isBoolean())throw new IllegalArgumentException("请明确确认或拒绝操作");
        return tools.confirm(owner,body.path("actionId").asText(),body.path("approved").asBoolean());
    }
    @PostMapping("/conversations") public Object create(@RequestBody JsonNode body){return chats.create(body.path("providerId").asText(),body.path("authorizationMode").asText("ask"));}
    @GetMapping("/conversations/{id}") public Object status(@PathVariable String id,@RequestParam(defaultValue="0") int since){return chats.status(id,since);}
    @PostMapping("/conversations/{id}/authorization") public Object authorization(@PathVariable String id,@RequestBody JsonNode body){return chats.setAuthorizationMode(id,body.path("mode").asText());}
    @PostMapping("/conversations/{id}/provider") public Object provider(@PathVariable String id,@RequestBody JsonNode body){return chats.setProvider(id,body.path("providerId").asText());}
    @NoWrap @GetMapping(value="/conversations/{id}/events",produces="text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(@PathVariable String id,@RequestParam(defaultValue="0") int since,
            @RequestHeader(value="Last-Event-ID",required=false) String lastEvent,HttpServletResponse response){
        response.setHeader("X-Accel-Buffering","no");
        if(lastEvent!=null)try{since=Math.max(since,Integer.parseInt(lastEvent));}catch(NumberFormatException ignored){}
        return streams.open(id,since);
    }
    @PostMapping("/conversations/{id}/messages") public Object message(@PathVariable String id,@RequestBody AiChatService.MessageRequest request) throws Exception{return chats.send(id,request);}
    @PostMapping("/conversations/{id}/approve") public Object approve(@PathVariable String id,@RequestBody JsonNode body){
        if(!body.path("approved").isBoolean()||!body.path("actionIds").isArray())throw new IllegalArgumentException("操作确认参数无效");
        List<String> ids=json.convertValue(body.get("actionIds"),new TypeReference<List<String>>(){});
        return chats.approve(id,ids,body.path("approved").asBoolean());
    }
    @PostMapping("/conversations/{id}/stop") public Object stop(@PathVariable String id){return chats.stop(id);}
    @DeleteMapping("/conversations/{id}") public void delete(@PathVariable String id){chats.delete(id);}
    @PostMapping("/conversations/{id}/attachments") public Object attachments(@PathVariable String id,@RequestParam("files") List<MultipartFile> uploaded,@RequestParam String paths) throws Exception {
        chats.requireOwner(id);List<String> names=json.readValue(paths,new TypeReference<List<String>>(){});return files.add(id,uploaded,names);
    }
    @NoWrap @GetMapping("/downloads/{id}") public ResponseEntity<InputStreamResource> download(@PathVariable String id) throws Exception{return files.download(id);}
}
