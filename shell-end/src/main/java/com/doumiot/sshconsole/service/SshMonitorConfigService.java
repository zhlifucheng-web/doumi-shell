package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Iterator;

@Service @SshConsoleEnabled
public class SshMonitorConfigService {
    private static final Set<String> CARD_TYPES = new HashSet<>(Arrays.asList("text", "value", "progress", "json", "line", "bar", "pie"));
    @Resource private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS ssh_monitor_config (account_id INTEGER PRIMARY KEY, config_json TEXT NOT NULL, updated_at INTEGER NOT NULL)");
        try { jdbc.execute("ALTER TABLE ssh_monitor_config ADD COLUMN revision INTEGER NOT NULL DEFAULT 1"); }
        catch (org.springframework.dao.DataAccessException ignored) { }
    }

    public synchronized JsonNode get(long accountId) {
        requireAccount(accountId);
        return jdbc.query("SELECT config_json,revision FROM ssh_monitor_config WHERE account_id=?", rs -> {
            if (!rs.next()) { com.fasterxml.jackson.databind.node.ObjectNode empty=defaultConfig();empty.put("revision",0);return empty; }
            try { com.fasterxml.jackson.databind.node.ObjectNode config=(com.fasterxml.jackson.databind.node.ObjectNode)normalize(mapper.readTree(rs.getString(1)));config.put("revision",rs.getLong(2));return config; }
            catch (Exception e) { throw new IllegalStateException("监控配置无法解析", e); }
        }, accountId);
    }

    public synchronized JsonNode save(long accountId, JsonNode config) {
        requireAccount(accountId);
        if(config==null||!config.path("revision").canConvertToLong())throw new IllegalArgumentException("监控配置缺少 revision，请先重新加载");
        return saveAtRevision(accountId,config,config.path("revision").asLong());
    }

    public synchronized JsonNode saveImported(long accountId, JsonNode config) {
        requireAccount(accountId);
        return saveAtRevision(accountId,config,get(accountId).path("revision").asLong());
    }

    private JsonNode saveAtRevision(long accountId, JsonNode config, long expectedRevision) {
        JsonNode current=get(accountId);
        long actualRevision=current.path("revision").asLong();
        if(actualRevision!=expectedRevision)throw new IllegalArgumentException("监控配置版本冲突：当前版本为 "+actualRevision+"，提交版本为 "+expectedRevision+"；已拒绝覆盖，请重新加载后再修改");
        JsonNode normalized = normalize(config);
        String json = normalized.toString();
        long nextRevision=actualRevision+1;
        int updated = jdbc.update("UPDATE ssh_monitor_config SET config_json=?,updated_at=?,revision=? WHERE account_id=? AND revision=?", json, System.currentTimeMillis(),nextRevision,accountId,expectedRevision);
        if (updated == 0) {
            if(actualRevision!=0)throw new IllegalArgumentException("监控配置版本冲突：数据已被其他操作更新，请重新加载后再修改");
            try { jdbc.update("INSERT INTO ssh_monitor_config(account_id,config_json,updated_at,revision) VALUES(?,?,?,?)",json,System.currentTimeMillis(),accountId,nextRevision); }
            catch(org.springframework.dao.DuplicateKeyException e){throw new IllegalArgumentException("监控配置版本冲突：数据已被其他操作创建，请重新加载后再修改");}
        }
        com.fasterxml.jackson.databind.node.ObjectNode response=(com.fasterxml.jackson.databind.node.ObjectNode)normalized;response.put("revision",nextRevision);return response;
    }

    public synchronized JsonNode createCard(long accountId, String name, String script, String type, String emptyText, String dashboardId) {
        JsonNode current = get(accountId);
        if (!current.path("customScripts").isArray()) ((com.fasterxml.jackson.databind.node.ObjectNode) current).putArray("customScripts");
        com.fasterxml.jackson.databind.node.ArrayNode cards = (com.fasterxml.jackson.databind.node.ArrayNode) current.path("customScripts");
        if (cards.size() >= 20) throw new IllegalArgumentException("自定义监控卡片最多 20 个");
        com.fasterxml.jackson.databind.node.ObjectNode card = mapper.createObjectNode();
        card.put("id", java.util.UUID.randomUUID().toString()); card.put("name", name); card.put("script", script);
        card.put("type", CARD_TYPES.contains(type) ? type : "text"); card.put("enabled", true);
        card.put("emptyText", boundedText(emptyText, "等待有效数据", 100));
        cards.add(card);
        String activeId=dashboardId==null?"":dashboardId.trim();
        if(activeId.isEmpty())throw new IllegalArgumentException("创建卡片时必须指定目标监控面板 ID");
        boolean dashboardFound=false;
        for(JsonNode dashboard:current.path("dashboards"))if(activeId.equals(dashboard.path("id").asText())){
            dashboardFound=true;
            com.fasterxml.jackson.databind.node.ObjectNode layout=(com.fasterxml.jackson.databind.node.ObjectNode)dashboard;
            if(!layout.path("customCardIds").isArray())layout.putArray("customCardIds");
            if(!layout.path("customCardIds").toString().contains("\""+card.path("id").asText()+"\""))layout.withArray("customCardIds").add(card.path("id").asText());
            if(!layout.path("cardOrder").toString().contains("\"custom:"+card.path("id").asText()+"\""))layout.withArray("cardOrder").add("custom:"+card.path("id").asText());
        }
        if(!dashboardFound)throw new IllegalArgumentException("监控面板不存在: "+activeId);
        JsonNode saved=saveImported(accountId,current);
        return getCardFromConfig(saved,card.path("id").asText());
    }

    public synchronized JsonNode listDashboards(long accountId) {
        JsonNode config=get(accountId);
        com.fasterxml.jackson.databind.node.ArrayNode result=mapper.createArrayNode();
        for(JsonNode dashboard:config.path("dashboards")){
            com.fasterxml.jackson.databind.node.ObjectNode row=mapper.createObjectNode();
            row.put("id",dashboard.path("id").asText());row.put("name",dashboard.path("name").asText());
            com.fasterxml.jackson.databind.node.ArrayNode ids=row.putArray("customCardIds");
            if(dashboard.path("customCardIds").isArray())dashboard.path("customCardIds").forEach(ids::add);
            result.add(row);
        }
        return result;
    }

    public synchronized JsonNode listCards(long accountId, String dashboardId) {
        JsonNode config = get(accountId);
        JsonNode cards=config.path("customScripts");com.fasterxml.jackson.databind.node.ArrayNode result=mapper.createArrayNode();
        if(!cards.isArray())return result;
        requireDashboard(config,dashboardId);
        for(JsonNode card:cards){
            com.fasterxml.jackson.databind.node.ArrayNode assigned=dashboardIds(config,card.path("id").asText());
            if(dashboardId!=null&&!contains(assigned,dashboardId))continue;
            com.fasterxml.jackson.databind.node.ObjectNode row=card.deepCopy();row.set("dashboardIds",assigned);result.add(row);
        }
        return result;
    }

    public synchronized JsonNode getCard(long accountId, String cardId, String dashboardId) {
        JsonNode cards = listCards(accountId, dashboardId);
        for (JsonNode card : cards) if (cardId.equals(card.path("id").asText())) return card;
        throw new IllegalArgumentException("自定义监控卡片不存在: " + cardId);
    }

    public synchronized JsonNode updateCard(long accountId, String cardId, String name, String script, String type, String emptyText, Boolean enabled, String dashboardId, Boolean visible) {
        JsonNode current = get(accountId);
        if(visible!=null&&(dashboardId==null||dashboardId.trim().isEmpty()))throw new IllegalArgumentException("设置卡片面板显示状态时必须提供 dashboardId");
        com.fasterxml.jackson.databind.node.ArrayNode cards = (com.fasterxml.jackson.databind.node.ArrayNode) current.withArray("customScripts");
        com.fasterxml.jackson.databind.node.ObjectNode found = null;
        for (JsonNode card : cards) if (cardId.equals(card.path("id").asText())) { found = (com.fasterxml.jackson.databind.node.ObjectNode) card; break; }
        if (found == null) throw new IllegalArgumentException("自定义监控卡片不存在: " + cardId);
        if (name != null) found.put("name", name.trim());
        if (script != null) found.put("script", script);
        if (type != null) found.put("type", type);
        if (emptyText != null) found.put("emptyText", boundedText(emptyText, "等待有效数据", 100));
        if (enabled != null) found.put("enabled", enabled);
        if(dashboardId!=null&&!dashboardId.trim().isEmpty()&&visible!=null) {
            com.fasterxml.jackson.databind.node.ObjectNode layout=requireDashboard(current,dashboardId.trim());
            com.fasterxml.jackson.databind.node.ArrayNode ids=layout.withArray("customCardIds");
            String id=cardId.trim();
            if(visible&&!contains(ids,id)){ids.add(id);layout.withArray("cardOrder").add("custom:"+id);}
            if(!visible){removeArrayValue(ids,id);removeArrayValue(layout.path("cardOrder"),"custom:"+id);}
        }
        JsonNode normalized = saveImported(accountId, current);
        return getCardFromConfig(normalized, cardId);
    }

    public synchronized JsonNode deleteCard(long accountId, String cardId, String dashboardId) {
        JsonNode current = get(accountId);
        if(dashboardId!=null&&!dashboardId.trim().isEmpty()){
            getCardFromConfig(current,cardId);
            com.fasterxml.jackson.databind.node.ObjectNode layout=requireDashboard(current,dashboardId.trim());
            removeArrayValue(layout.path("customCardIds"),cardId);removeArrayValue(layout.path("cardOrder"),"custom:"+cardId);
            saveImported(accountId,current);
            com.fasterxml.jackson.databind.node.ObjectNode result=mapper.createObjectNode();result.put("unassigned",true);result.put("cardId",cardId);result.put("dashboardId",dashboardId);return result;
        }
        com.fasterxml.jackson.databind.node.ArrayNode cards = (com.fasterxml.jackson.databind.node.ArrayNode) current.withArray("customScripts");
        boolean removed = false;
        for (Iterator<JsonNode> it = cards.iterator(); it.hasNext();) {
            if (cardId.equals(it.next().path("id").asText())) { it.remove(); removed = true; break; }
        }
        if (!removed) throw new IllegalArgumentException("自定义监控卡片不存在: " + cardId);
        JsonNode order = current.path("cardOrder");
        if (order.isArray()) for (Iterator<JsonNode> it = order.iterator(); it.hasNext();) if (("custom:" + cardId).equals(it.next().asText())) it.remove();
        for(JsonNode dashboard:current.path("dashboards"))if(dashboard instanceof com.fasterxml.jackson.databind.node.ObjectNode){
            com.fasterxml.jackson.databind.node.ObjectNode layout=(com.fasterxml.jackson.databind.node.ObjectNode)dashboard;
            removeArrayValue(layout.path("customCardIds"),cardId);removeArrayValue(layout.path("cardOrder"),"custom:"+cardId);
        }
        saveImported(accountId, current);
        com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
        result.put("deleted", true); result.put("cardId", cardId);
        return result;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode requireDashboard(JsonNode config,String dashboardId){
        if(dashboardId==null)return null;
        for(JsonNode dashboard:config.path("dashboards"))if(dashboardId.equals(dashboard.path("id").asText()))return (com.fasterxml.jackson.databind.node.ObjectNode)dashboard;
        throw new IllegalArgumentException("监控面板不存在: "+dashboardId);
    }
    private com.fasterxml.jackson.databind.node.ArrayNode dashboardIds(JsonNode config,String cardId){
        com.fasterxml.jackson.databind.node.ArrayNode result=mapper.createArrayNode();
        for(JsonNode dashboard:config.path("dashboards"))if(contains(dashboard.path("customCardIds"),cardId))result.add(dashboard.path("id").asText());
        return result;
    }
    private boolean contains(JsonNode values,String value){if(values!=null&&values.isArray())for(JsonNode item:values)if(value.equals(item.asText()))return true;return false;}

    private JsonNode getCardFromConfig(JsonNode config, String cardId) {
        for (JsonNode card : config.path("customScripts")) if (cardId.equals(card.path("id").asText())) return card;
        throw new IllegalArgumentException("自定义监控卡片不存在: " + cardId);
    }

    public synchronized Map<Long, JsonNode> exportAll() {
        Map<Long, JsonNode> result = new LinkedHashMap<>();
        jdbc.query("SELECT c.account_id,c.config_json FROM ssh_monitor_config c JOIN ssh_account a ON a.id=c.account_id ORDER BY c.account_id", rs -> {
            try { result.put(rs.getLong(1), mapper.readTree(rs.getString(2))); }
            catch (Exception e) { throw new IllegalStateException("监控配置无法解析", e); }
        });
        return result;
    }

    private JsonNode normalize(JsonNode input) {
        if (input == null || !input.isObject()) throw new IllegalArgumentException("监控配置格式无效");
        com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
        JsonNode scripts = input.path("customScripts");
        if(!scripts.isArray())throw new IllegalArgumentException("监控配置缺少 customScripts");
        com.fasterxml.jackson.databind.node.ArrayNode scriptOut = out.putArray("customScripts");
        Set<String> cardIds=new HashSet<>();
        if (scripts.isArray()) {
            if (scripts.size() > 20) throw new IllegalArgumentException("自定义监控卡片最多 20 个");
            scripts.forEach(card -> {
                String id = card.path("id").asText(), name = card.path("name").asText().trim(), script = card.path("script").asText();
                String type = card.path("type").asText("text");
                if (id.isEmpty() || !cardIds.add(id) || name.isEmpty() || name.length() > 50 || script.isEmpty() || script.length() > 12000 || !CARD_TYPES.contains(type)) throw new IllegalArgumentException("自定义监控卡片字段无效");
                String emptyText=boundedText(card.path("emptyText").asText("等待有效数据"),"等待有效数据",100);
                com.fasterxml.jackson.databind.node.ObjectNode row = mapper.createObjectNode(); row.put("id", id); row.put("name", name); row.put("script", script); row.put("type", type); row.put("enabled", card.path("enabled").asBoolean(true)); row.put("emptyText",emptyText); scriptOut.add(row);
            });
        }
        out.put("interval", bounded(input.path("interval").asInt(5000), 5000, 5000, 30000));
        com.fasterxml.jackson.databind.node.ArrayNode dashboardOut=out.putArray("dashboards");
        JsonNode dashboardInput=input.path("dashboards");
        if(!dashboardInput.isArray()||dashboardInput.isEmpty()) {
            com.fasterxml.jackson.databind.node.ObjectNode fallback=mapper.createObjectNode();
            fallback.put("id","default");fallback.put("name","默认面板");
            fallback.putArray("selectedCards").add("cpu").add("memory").add("network").add("gpu").add("disk");
            fallback.putArray("customCardIds");fallback.putArray("cardOrder").add("cpu").add("memory").add("network").add("gpu").add("disk");
            fallback.put("minutes",5);fallback.put("iface","");
            dashboardInput=mapper.createArrayNode().add(fallback);
        }
        Set<String> dashboardIds=new HashSet<>();
        if(dashboardInput.isArray()&&!dashboardInput.isEmpty()){
            if(dashboardInput.size()>12)throw new IllegalArgumentException("监控面板最多 12 个");
            for(JsonNode dashboard:dashboardInput){
                String id=dashboard.path("id").asText().trim(),name=dashboard.path("name").asText().trim();
                if(id.isEmpty()||id.length()>64||name.isEmpty()||name.length()>40||!dashboardIds.add(id))throw new IllegalArgumentException("监控面板名称或 ID 无效");
                com.fasterxml.jackson.databind.node.ObjectNode row=mapper.createObjectNode();row.put("id",id);row.put("name",name);
                if(!dashboard.path("selectedCards").isArray()||!dashboard.path("customCardIds").isArray()||!dashboard.path("cardOrder").isArray())throw new IllegalArgumentException("监控面板布局字段无效");
                com.fasterxml.jackson.databind.node.ArrayNode rowCards=row.putArray("selectedCards");JsonNode selection=dashboard.path("selectedCards");
                selection.forEach(v->{if(Arrays.asList("cpu","memory","network","gpu","disk").contains(v.asText())&&!rowCards.toString().contains("\""+v.asText()+"\""))rowCards.add(v.asText());});
                com.fasterxml.jackson.databind.node.ArrayNode customIds=row.putArray("customCardIds");JsonNode selectedCustom=dashboard.path("customCardIds");
                selectedCustom.forEach(v->{String idValue=v.asText();if(cardIds.contains(idValue)&&!customIds.toString().contains("\""+idValue+"\""))customIds.add(idValue);});
                com.fasterxml.jackson.databind.node.ArrayNode rowOrder=row.putArray("cardOrder");JsonNode savedOrder=dashboard.path("cardOrder");
                Set<String> allowed=new HashSet<>(Arrays.asList("cpu","memory","network","gpu","disk"));cardIds.forEach(v->allowed.add("custom:"+v));
                savedOrder.forEach(v->{if(allowed.contains(v.asText())&&!rowOrder.toString().contains("\""+v.asText()+"\""))rowOrder.add(v.asText());});
                Arrays.asList("cpu","memory","network","gpu","disk").forEach(v->{if(!rowOrder.toString().contains("\""+v+"\""))rowOrder.add(v);});
                customIds.forEach(v->{String key="custom:"+v.asText();if(!rowOrder.toString().contains("\""+key+"\""))rowOrder.add(key);});
                row.put("minutes",bounded(dashboard.path("minutes").asInt(input.path("minutes").asInt(5)),5,1,30));
                row.put("iface",dashboard.path("iface").asText(input.path("iface").asText("")));
                dashboardOut.add(row);
            }
        }
        String activeDashboardId = input.path("activeDashboardId").asText("").trim();
        boolean activeFound = false;
        for (JsonNode dashboard : dashboardOut) if (activeDashboardId.equals(dashboard.path("id").asText())) { activeFound = true; break; }
        out.put("activeDashboardId", activeFound ? activeDashboardId : dashboardOut.get(0).path("id").asText());
        return out;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode defaultConfig() {
        com.fasterxml.jackson.databind.node.ObjectNode config=mapper.createObjectNode();
        config.putArray("customScripts");config.put("interval",5000);config.put("activeDashboardId","default");
        com.fasterxml.jackson.databind.node.ObjectNode dashboard=config.putArray("dashboards").addObject();
        dashboard.put("id","default");dashboard.put("name","默认面板");
        dashboard.putArray("selectedCards").add("cpu").add("memory").add("network").add("gpu").add("disk");
        dashboard.putArray("customCardIds");dashboard.putArray("cardOrder").add("cpu").add("memory").add("network").add("gpu").add("disk");
        dashboard.put("minutes",5);dashboard.put("iface","");
        return config;
    }

    public synchronized JsonNode updateDashboard(long accountId,String dashboardId,JsonNode layout){
        JsonNode current=get(accountId);
        com.fasterxml.jackson.databind.node.ObjectNode dashboard=requireDashboard(current,dashboardId);
        if(layout==null||!layout.isObject())throw new IllegalArgumentException("面板布局必须是对象");
        JsonNode selected=layout.path("selectedCards");
        if(selected.isArray())dashboard.set("selectedCards",selected.deepCopy());
        JsonNode custom=layout.path("customCardIds");
        if(custom.isArray())dashboard.set("customCardIds",custom.deepCopy());
        JsonNode order=layout.path("cardOrder");
        if(order.isArray())dashboard.set("cardOrder",order.deepCopy());
        if(layout.has("minutes"))dashboard.put("minutes",bounded(layout.path("minutes").asInt(5),5,1,30));
        if(layout.has("iface"))dashboard.put("iface",layout.path("iface").asText(""));
        JsonNode saved=saveImported(accountId,current);
        for(JsonNode item:saved.path("dashboards"))if(dashboardId.equals(item.path("id").asText()))return item;
        throw new IllegalArgumentException("监控面板不存在: "+dashboardId);
    }

    private void removeArrayValue(JsonNode values,String value){if(values.isArray())for(Iterator<JsonNode>it=values.iterator();it.hasNext();)if(value.equals(it.next().asText()))it.remove();}

    private int bounded(int value, int fallback, int min, int max) { return value >= min && value <= max ? value : fallback; }
    private String boundedText(String value,String fallback,int max){String text=value==null?"":value.trim();if(text.length()>max)throw new IllegalArgumentException("无数据提示最多 "+max+" 个字符");return text.isEmpty()?fallback:text;}
    private void requireAccount(long accountId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ssh_account WHERE id=?", Integer.class, accountId);
        if (count == null || count == 0) throw new IllegalArgumentException("SSH 账户不存在: " + accountId);
    }
}
