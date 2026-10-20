package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.ai.AiChatService;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import javax.annotation.Resource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 清理仅使用白名单；引用保护避免级联删除未选中或时间范围外的数据。 */
@Service @SshConsoleEnabled
public class SshDataCleanupService {
    @Resource private JdbcTemplate jdbc;
    @Resource private PlatformTransactionManager transactions;
    @Resource private SshScriptExecutionService executions;
    @Resource private SshSessionRegistry sessions;
    @Resource private AiChatService chats;
    private final Map<String,Ticket> tickets=new ConcurrentHashMap<>();
    private static final List<String> TABLES=Arrays.asList("ssh_script_execution","ssh_script_schedule","ssh_script","ssh_account","ssh_host","ssh_group","ai_conversation");
    private static final List<String> LABELS=Arrays.asList("脚本执行历史","定时计划","脚本","SSH 账户","主机","分组","AI 会话历史");
    @Data public static class Request {
        private List<String> tables;
        private String from;
        private String to;
        private boolean allTime;
    }
    @Data public static class ClearRequest {private String token;}

    public Object tables() {
        List<Map<String,Object>> result=new ArrayList<>();
        for(int i=0;i<TABLES.size();i++) {Map<String,Object> row=new LinkedHashMap<>();row.put("name",TABLES.get(i));row.put("label",LABELS.get(i));row.put("timeColumn",i==0?"执行开始时间":"创建时间");result.add(row);}
        return result;
    }
    private Request validate(Request value) {
        if(value.getTables()==null || value.getTables().isEmpty() || !TABLES.containsAll(value.getTables())) throw new IllegalArgumentException("请选择有效的数据表");
        Request copy=new Request();copy.setTables(new ArrayList<>(new LinkedHashSet<>(value.getTables())));copy.setAllTime(value.isAllTime());
        if(!value.isAllTime()) {
            try {
                Instant from=Instant.parse(value.getFrom()),to=Instant.parse(value.getTo());
                if(from.isAfter(to)) throw new IllegalArgumentException();
                copy.setFrom(from.toString());copy.setTo(to.toString());
            }catch(Exception e) {throw new IllegalArgumentException("请选择有效的开始和结束时间");}
        }
        return copy;
    }
    public Object preview(Request request) {
        Request normalized=validate(request);
        synchronized(executions.maintenanceLock) {
            Plan plan=new TransactionTemplate(transactions).execute(tx -> plan(normalized));
            tickets.entrySet().removeIf(e -> e.getValue().expires<System.currentTimeMillis());
            if(tickets.size()>100) tickets.clear();
            String token=UUID.randomUUID().toString();tickets.put(token,new Ticket(normalized,plan));
            Map<String,Object> result=new LinkedHashMap<>();result.put("token",token);result.put("rows",plan.counts);return result;
        }
    }
    public Object clear(String token) {
        if(token==null) throw new IllegalArgumentException("请先预览清理范围");
        synchronized(executions.maintenanceLock) {
            Ticket ticket=tickets.remove(token);
            if(ticket==null || ticket.expires<System.currentTimeMillis()) throw new IllegalArgumentException("预览已过期，请重新预览");
            Plan result=new TransactionTemplate(transactions).execute(tx -> {
                Plan current=plan(ticket.request);
                if(!current.ids.equals(ticket.plan.ids) || !current.counts.equals(ticket.plan.counts)) throw new IllegalArgumentException("数据已变化，请重新预览");
                for(String table:TABLES) {
                    Set<String> pending=new LinkedHashSet<>(current.ids.get(table));
                    if("ssh_group".equals(table)) {
                        while(!pending.isEmpty()) {
                            boolean progress=false;
                            for(String id:new ArrayList<>(pending)) {
                                if(jdbc.queryForObject("SELECT COUNT(*) FROM ssh_group WHERE parent_id=?",Integer.class,id)==0) {
                                    jdbc.update("DELETE FROM ssh_group WHERE id=?",id);pending.remove(id);progress=true;
                                }
                            }
                            if(!progress) throw new IllegalArgumentException("分组存在循环引用，清理已回滚");
                        }
                    } else for(String id:pending) jdbc.update("DELETE FROM "+table+" WHERE "+key(table)+"=?",id);
                }
                return current;
            });
            for(String id:result.ids.get("ssh_account")) sessions.closeByAccountId(Long.parseLong(id));
            if(chats!=null)chats.forgetDeleted(result.ids.get("ai_conversation"));
            return result.counts;
        }
    }

    private Plan plan(Request request) {
        Plan p=new Plan();Map<String,List<Map<String,Object>>> all=new LinkedHashMap<>();Map<String,Integer> matched=new HashMap<>();
        for(String table:TABLES) {
            all.put(table,jdbc.queryForList("SELECT "+columns(table)+" FROM "+table));
            Set<String> ids=new LinkedHashSet<>();
            if(request.getTables().contains(table)) {
                String time=table.equals("ssh_script_execution")?"julianday(started_at)":table.equals("ai_conversation")?"julianday(created_at/1000.0,'unixepoch')":"julianday(created_at,'utc')";
                String sql="SELECT "+key(table)+" FROM "+table;
                List<Map<String,Object>> candidates=request.isAllTime()?jdbc.queryForList(sql):jdbc.queryForList(sql+" WHERE "+time+">=julianday(?) AND "+time+"<=julianday(?)",request.getFrom(),request.getTo());
                for(Map<String,Object> row:candidates)ids.add(String.valueOf(row.get(key(table))));
            }
            matched.put(table,ids.size());p.ids.put(table,ids);
        }
        for(Map<String,Object> row:all.get("ssh_script_execution")) if(Arrays.asList("QUEUED","RUNNING","CANCELLING").contains(row.get("status"))) {
            p.ids.get("ssh_script_execution").remove(str(row,"id"));
            p.ids.get("ssh_script").remove(str(row,"script_id"));p.ids.get("ssh_account").remove(str(row,"account_id"));p.ids.get("ssh_host").remove(str(row,"host_id"));
        }
        for(Map<String,Object> row:all.get("ai_conversation")) if(Arrays.asList("running","stopping","awaiting_confirmation").contains(row.get("state")))
            p.ids.get("ai_conversation").remove(str(row,"id"));
        boolean changed;
        do {
            changed=false;
            changed|=protect(p,all,"ssh_script_schedule","script_id","ssh_script");
            changed|=protect(p,all,"ssh_script_schedule","account_id","ssh_account");
            changed|=protect(p,all,"ssh_script","host_id","ssh_host");
            changed|=protect(p,all,"ssh_account","host_id","ssh_host");
            changed|=protect(p,all,"ssh_host","group_id","ssh_group");
            changed|=protect(p,all,"ssh_group","parent_id","ssh_group");
        }while(changed);
        for(int i=0;i<TABLES.size();i++) {
            String table=TABLES.get(i);if(!request.getTables().contains(table))continue;
            Map<String,Object> row=new LinkedHashMap<>();row.put("table",table);row.put("label",LABELS.get(i));row.put("matched",matched.get(table));row.put("deletable",p.ids.get(table).size());row.put("protected",matched.get(table)-p.ids.get(table).size());p.counts.add(row);
        }
        return p;
    }
    private boolean protect(Plan p,Map<String,List<Map<String,Object>>> all,String child,String foreign,String parent) {
        boolean changed=false;
        for(Map<String,Object> row:all.get(child)) if(!p.ids.get(child).contains(str(row,key(child)))) changed|=p.ids.get(parent).remove(str(row,foreign));
        return changed;
    }
    private static String str(Map<String,Object> row,String key) {return String.valueOf(row.get(key));}
    private static String key(String table) {return table.equals("ssh_script_schedule")?"script_id":"id";}
    private static String columns(String table) {
        switch(table) {
            case "ssh_script_execution":return "id,script_id,account_id,host_id,status";
            case "ai_conversation":return "id,state";
            case "ssh_script_schedule":return "script_id,account_id";
            case "ssh_script":case "ssh_account":return "id,host_id";
            case "ssh_host":return "id,group_id";
            default:return "id,parent_id";
        }
    }
    private static class Plan {final Map<String,Set<String>> ids=new LinkedHashMap<>();final List<Map<String,Object>> counts=new ArrayList<>();}
    private static class Ticket {
        final Request request;final Plan plan;final long expires=System.currentTimeMillis()+300000;
        Ticket(Request request,Plan plan){this.request=request;this.plan=plan;}
    }
}
