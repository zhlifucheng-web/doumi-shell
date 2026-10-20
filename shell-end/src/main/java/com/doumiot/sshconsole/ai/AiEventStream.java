package com.doumiot.sshconsole.ai;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import javax.annotation.Resource;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** Push coalesced snapshots; slow browsers do not hold chat or tool locks. */
@Service @SshConsoleEnabled
public class AiEventStream {
    @Resource private AiChatService chats;
    private final Set<Subscription> subscriptions=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService pushers=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"doumi-ai-events");t.setDaemon(true);return t;});
    private static class Subscription {String id,signature="";int cursor;long sent;SseEmitter emitter;volatile ScheduledFuture<?> future;volatile boolean closed;}
    public synchronized SseEmitter open(String id,int since) {
        chats.requireOwner(id);
        if(subscriptions.size()>=32||subscriptions.stream().filter(s->s.id.equals(id)).count()>=4)throw new IllegalArgumentException("AI 实时连接过多，请关闭多余页面后重试");
        Subscription s=new Subscription();s.id=id;s.cursor=Math.max(0,since);s.emitter=new SseEmitter(30*60*1000L);subscriptions.add(s);
        s.emitter.onCompletion(()->remove(s));s.emitter.onTimeout(()->{remove(s);s.emitter.complete();});s.emitter.onError(e->remove(s));
        s.future=pushers.scheduleWithFixedDelay(()->push(s),0,80,TimeUnit.MILLISECONDS);
        if(s.closed)s.future.cancel(false);
        return s.emitter;
    }
    private void push(Subscription s) {
        if(s.closed)return;
        try {
            JsonNode snapshot=chats.status(s.id,s.cursor);
            String signature=snapshot.path("cursor")+":"+snapshot.path("running")+":"+snapshot.path("state");
            long now=System.currentTimeMillis();
            if(!signature.equals(s.signature)) {
                s.emitter.send(SseEmitter.event().name("snapshot").id(snapshot.path("cursor").asText()).data(snapshot));
                s.cursor=snapshot.path("cursor").asInt();s.signature=signature;s.sent=now;
            }else if(now-s.sent>=15000){s.emitter.send(SseEmitter.event().comment("keepalive"));s.sent=now;}
        }catch(IllegalArgumentException e){
            try{s.emitter.send(SseEmitter.event().name("expired").data("AI 会话不存在或已过期，请开启新会话"));}catch(Exception ignored){}
            remove(s);s.emitter.complete();
        }catch(Exception e){remove(s);s.emitter.completeWithError(e);}
    }
    private void remove(Subscription s){s.closed=true;subscriptions.remove(s);if(s.future!=null)s.future.cancel(false);}
    @PreDestroy void close(){new ArrayList<>(subscriptions).forEach(s->{remove(s);s.emitter.complete();});pushers.shutdownNow();}
}
