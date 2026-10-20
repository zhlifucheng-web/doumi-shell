package com.doumiot.sshconsole.ai;

import com.doumiot.config.ConfigurationArchive;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.*;
import com.doumiot.sshconsole.ssh.SshRemoteExec;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** One allowlisted tool catalog for both the built-in chat and external AI clients. */
@Service @SshConsoleEnabled
public class AiTools {
    @Resource private SshTreeService tree;
    @Resource private SshGroupService groups;
    @Resource private SshHostService hosts;
    @Resource private SshAccountService accounts;
    @Resource private SshHostMonitorService monitor;
    @Resource private SshSftpService sftp;
    @Resource private SshFolderTransferService folders;
    @Resource private SshScriptService scripts;
    @Resource private SshScriptScheduleService schedules;
    @Resource private SshScriptExecutionService executions;
    @Resource private SshProcessService processes;
    @Resource private SshDiskUsageService disks;
    @Resource private SshConfigurationService configuration;
    @Resource private SshDataCleanupService cleanup;
    @Resource private AiConnections connections;
    @Resource private AiFiles files;
    private final Map<String,Operation> operations = new LinkedHashMap<>();
    private final Map<String,Pending> pending = new ConcurrentHashMap<>();
    interface Handler { Object execute(JsonNode args, String owner) throws Exception; }
    private static class Operation {
        String name, page, description; boolean confirmation, writes; ObjectNode parameters; Handler handler;
    }
    private static class Pending {
        String id, owner, name; JsonNode args; long expires;
    }
    @PostConstruct void initialize() {
        add("tree_children","terminal","列出分组、主机或账户；从 parentType=ROOT 开始，GROUP 的 parentId 是分组 ID，HOST 的 parentId 是主机 ID",false,false,
                (a,o)->tree.listChildren(AiJson.text(a,"parentType"),optionalId(a,"parentId")),"parentType!:string:ROOT/GROUP/HOST","parentId:integer:父节点 ID");
        add("group_get","terminal","读取分组",false,false,(a,o)->groups.getById(AiJson.id(a,"id")),"id!:integer:分组 ID");
        add("group_create","terminal","创建分组，data 包含 name、可选 parentId/remark/sortOrder",false,true,
                (a,o)->groups.create(body(a,SshGroupService.SshGroupSaveRequest.class)),"data!:object:分组字段 {name,parentId?,remark?,sortOrder?}");
        add("group_update","terminal","修改分组，data 包含完整 name、parentId、remark、sortOrder",true,true,
                (a,o)->groups.update(AiJson.id(a,"id"),body(a,SshGroupService.SshGroupSaveRequest.class)),"id!:integer:分组 ID","data!:object:完整分组字段");
        add("group_move","terminal","移动分组；不填 parentId 表示根分组",true,true,
                (a,o)->groups.move(AiJson.id(a,"id"),optionalId(a,"parentId")),"id!:integer:分组 ID","parentId:integer:父分组 ID");
        add("group_delete","terminal","删除空分组",true,true,(a,o)->groups.delete(AiJson.id(a,"id")),"id!:integer:分组 ID");
        add("host_get","terminal","读取主机配置",false,false,(a,o)->hosts.getById(AiJson.id(a,"id")),"id!:integer:主机 ID");
        add("host_create","terminal","创建主机，data 必填 groupId/name/host，port 默认22，可填 remark/enabled/sortOrder",false,true,
                (a,o)->hosts.create(body(a,SshHostService.SshHostSaveRequest.class)),"data!:object:主机字段 {groupId,name,host,port?,remark?,enabled?,sortOrder?}");
        add("host_update","terminal","修改主机，先读取再提交完整配置",true,true,
                (a,o)->hosts.update(AiJson.id(a,"id"),body(a,SshHostService.SshHostSaveRequest.class)),"id!:integer:主机 ID","data!:object:完整主机配置");
        add("host_move","terminal","将主机移至指定分组",true,true,(a,o)->hosts.move(AiJson.id(a,"id"),AiJson.id(a,"groupId")),"id!:integer:主机 ID","groupId!:integer:目标分组 ID");
        add("host_delete","terminal","删除主机，需要先移除账户",true,true,(a,o)->hosts.delete(AiJson.id(a,"id")),"id!:integer:主机 ID");
        add("account_get","terminal","读取账户配置，不返回密码或私钥",false,false,(a,o)->accounts.getById(AiJson.id(a,"id")),"id!:integer:账户 ID");
        add("account_create","terminal","创建账户。data 必填 hostId/username/authType/secret。authType=PASSWORD/PRIVATE_KEY/PRIVATE_KEY_WITH_PASSPHRASE；secret={password?,privateKeyPem?,passphrase?}；enabled=0 禁用未配置凭据的账户",false,true,
                (a,o)->accounts.create(body(a,SshAccountService.SshAccountSaveRequest.class)),"data!:object:账户配置，可含 defaultCwd/remark/enabled/sortOrder");
        add("account_update","terminal","修改账户。先读取，提交完整 username/authType/enabled 等字段；secret 不传则保留。更新后关闭 AI 自有连接",true,true,
                (a,o)->{long id=AiJson.id(a,"id"); Object result=accounts.update(id,body(a,SshAccountService.SshAccountSaveRequest.class));connections.close(id);return result;},"id!:integer:账户 ID","data!:object:完整账户配置，secret 可选");
        add("account_delete","terminal","删除账户并关闭连接",true,true,(a,o)->{long id=AiJson.id(a,"id");connections.close(id);return accounts.delete(id);},"id!:integer:账户 ID");
        add("account_test","terminal","测试账户 SSH 连接与认证",false,false,(a,o)->accounts.testConnection(AiJson.id(a,"accountId")),"accountId!:integer:账户 ID");
        add("ssh_connect","terminal","建立或复用 AI 专用 SSH 会话，不影响页面终端",false,false,
                (a,o)->{long id=AiJson.id(a,"accountId");return AiJson.object("accountId",id,"label",connections.session(id).getLabel(),"connected",true);},"accountId!:integer:账户 ID");
        add("ssh_disconnect","terminal","关闭 AI 专用连接，不关闭页面终端",false,false,(a,o)->{connections.close(AiJson.id(a,"accountId"));return ok();},"accountId!:integer:账户 ID");
        add("ssh_command","terminal","执行一次 SSH 命令并返回退出码和输出；总是需要用户确认。长任务请创建脚本并用 script_run。超时关闭通道不保证撤回已执行的远端命令",true,true,
                (a,o)->{SshSessionRegistry.SessionEntry s=connections.session(AiJson.id(a,"accountId"));return SshRemoteExec.exec(s.getClient(),s.getExecLock(),AiJson.text(a,"command"),integer(a,"timeoutSeconds",30,1,60));},
                "accountId!:integer:账户 ID","command!:string:完整命令","timeoutSeconds:integer:1 至 60 秒，默认30");
        add("host_metrics","terminal","采样 CPU、内存、Swap、网络、磁盘监控",false,false,(a,o)->monitor.sample(token(a)),"accountId!:integer:账户 ID");
        add("file_list","terminal","列出远端目录及文件属性",false,false,(a,o)->sftp.list(token(a),optionalText(a,"path")),"accountId!:integer:账户 ID","path:string:远端目录，默认账户目录");
        add("file_directories","terminal","仅列出远端子目录，便于选择路径",false,false,(a,o)->sftp.directories(token(a),optionalText(a,"path")),"accountId!:integer:账户 ID","path:string:远端目录");
        add("file_read","terminal","读取远端 UTF-8 文本，默认前16384字节，最大65536字节",false,false,(a,o)->files.remoteRead(AiJson.id(a,"accountId"),AiJson.text(a,"path"),integer(a,"limit",16384,1,65536)),"accountId!:integer:账户 ID","path!:string:远端文件路径","limit:integer:字节上限");
        add("file_write","terminal","写入或覆盖远端文本文件，使用临时文件提交，最多1 MB",true,true,(a,o)->files.remoteWrite(AiJson.id(a,"accountId"),AiJson.text(a,"path"),a.path("content").asText()),"accountId!:integer:账户 ID","path!:string:远端文件绝对路径","content!:string:UTF-8 内容，可为空");
        add("file_upload","terminal","上传聊天附件。preserveDirectories=true 保留附件相对目录；archive=true 还原 ZIP 文件夹。普通文件使用分块上传，保留原文件直至提交成功",true,true,
                (a,o)->files.upload(o,AiJson.id(a,"accountId"),AiJson.text(a,"path"),AiJson.text(a,"attachmentId"),a.path("preserveDirectories").asBoolean(false),a.path("archive").asBoolean(false)),
                "accountId!:integer:账户 ID","path!:string:远端目标目录","attachmentId!:string:聊天附件 ID","preserveDirectories:boolean:保留相对目录","archive:boolean:将附件作为 ZIP 文件夹还原");
        add("attachment_read","data-management","分段读取本次聊天附件。只可访问用户已上传的附件，按 UTF-8 文本读取",false,false,(a,o)->files.read(o,AiJson.text(a,"attachmentId"),integer(a,"offset",0,0,2097152),integer(a,"limit",16384,1,32768)),"attachmentId!:string:附件 ID","offset:integer:字符偏移","limit:integer:最多32768字符");
        add("file_mkdir","terminal","创建远端目录",true,true,(a,o)->{sftp.mkdir(token(a),AiJson.text(a,"path"));return ok();},"accountId!:integer:账户 ID","path!:string:远端目录");
        add("file_rename","terminal","重命名远端文件或目录",true,true,(a,o)->{sftp.rename(token(a),AiJson.text(a,"path"),AiJson.text(a,"newName"));return ok();},"accountId!:integer:账户 ID","path!:string:原路径","newName!:string:新名称，不含目录");
        add("file_delete","terminal","删除远端文件或空目录，不递归删除非空目录",true,true,(a,o)->{sftp.delete(token(a),AiJson.text(a,"path"));return ok();},"accountId!:integer:账户 ID","path!:string:远端路径");
        add("file_download","terminal","生成远端文件或文件夹下载链接，由用户点击保存。大文件沿用流式下载；文件夹使用 ZIP，保留原有512MB等限制",false,false,
                (a,o)->files.remoteDownload(o,AiJson.id(a,"accountId"),AiJson.text(a,"path"),a.path("folder").asBoolean(false),a.path("compress").asBoolean(true)),"accountId!:integer:账户 ID","path!:string:远端路径","folder:boolean:是否文件夹","compress:boolean:文件夹压缩，默认true");
        add("folder_tree","terminal","读取远端文件夹结构，沿用现有层数和文件数限制",false,false,(a,o)->folders.tree(token(a),AiJson.text(a,"path")),"accountId!:integer:账户 ID","path!:string:远端目录");
        add("scripts_list","scripts","列出脚本及定时计划，hostId 可筛选主机",false,false,(a,o)->scripts.listForHost(optionalId(a,"hostId")),"hostId:integer:主机 ID");
        add("script_get","scripts","读取脚本内容和定时配置",false,false,(a,o)->scripts.getById(AiJson.id(a,"id")),"id!:integer:脚本 ID");
        String scriptFields="脚本字段 {name,content,hostId?,interpreter?,workDir?,remark?,sortOrder?,schedule?:{enabled,accountId,cron,zone}}；cron支持5/6段，zone默认Asia/Shanghai";
        add("script_create","scripts","创建脚本，可同时创建定时计划",true,true,(a,o)->scripts.create(body(a,SshScriptService.SshScriptSaveRequest.class)),"data!:object:"+scriptFields);
        add("script_update","scripts","修改脚本和定时计划，先读取再提交完整配置",true,true,(a,o)->scripts.update(AiJson.id(a,"id"),body(a,SshScriptService.SshScriptSaveRequest.class)),"id!:integer:脚本 ID","data!:object:"+scriptFields);
        add("script_delete","scripts","删除脚本和计划，保留已有执行历史",true,true,(a,o)->{scripts.delete(AiJson.id(a,"id"));return ok();},"id!:integer:脚本 ID");
        add("schedule_accounts","scripts","列出可用于脚本执行的账户",false,false,(a,o)->schedules.accountOptions());
        add("cron_preview","scripts","校验 cron 并预览后续执行时间",false,false,(a,o)->SshScriptScheduleService.preview(AiJson.text(a,"cron"),a.path("zone").asText("Asia/Shanghai")),"cron!:string:5或6段Cron","zone:string:时区，默认Asia/Shanghai");
        add("script_run","scripts","启动后台脚本执行，返回 executionId，随后使用 execution_get 查询结果",true,true,(a,o)->executions.start(AiJson.id(a,"scriptId"),AiJson.id(a,"accountId"),"MANUAL"),"scriptId!:integer:脚本 ID","accountId!:integer:执行账户 ID");
        add("executions_list","scripts","分页查询执行历史，可筛选脚本、状态、MANUAL/SCHEDULE来源",false,false,(a,o)->executions.list(optionalId(a,"scriptId"),optionalText(a,"status"),optionalText(a,"source"),integer(a,"page",1,1,100000),integer(a,"size",20,1,100)),"scriptId:integer:脚本 ID","status:string:状态","source:string:MANUAL/SCHEDULE","page:integer:页码","size:integer:每页数量，最大100");
        add("execution_get","scripts","读取执行状态、输出、错误和时间",false,false,(a,o)->executions.detail(AiJson.text(a,"id")),"id!:string:执行 ID");
        add("execution_stop","scripts","停止进行中的脚本执行",true,true,(a,o)->executions.stop(AiJson.text(a,"id")),"id!:string:执行 ID");
        add("processes_list","processes","列出服务器进程和命令，可用 keyword 筛选，limit默认100最大500",false,false,
                (a,o)->{List<SshProcessService.ProcessRow> rows=processes.listProcesses(token(a));String key=a.path("keyword").asText("").toLowerCase(Locale.ROOT);ArrayNode result=AiJson.JSON.createArrayNode();int limit=integer(a,"limit",100,1,500);for(Object row:rows){JsonNode node=AiJson.JSON.valueToTree(row);if(node.toString().toLowerCase(Locale.ROOT).contains(key)&&result.size()<limit)result.add(node);}return AiJson.object("total",rows.size(),"items",result);},
                "accountId!:integer:账户 ID","keyword:string:筛选关键词","limit:integer:返回条数上限");
        add("process_kill","processes","发送 TERM 或 KILL 停止指定进程",true,true,(a,o)->processes.killProcess(token(a),AiJson.id(a,"pid"),a.path("signal").asText("TERM")),"accountId!:integer:账户 ID","pid!:integer:进程 PID","signal:string:TERM或KILL，默认TERM");
        add("disk_scan","disk-usage","扫描目录空间与大文件，只返回当前层级，沿用Linux GNU工具等限制",false,false,(a,o)->disks.scan(token(a),optionalText(a,"path"),integer(a,"minimumMb",100,0,1048576)),"accountId!:integer:账户 ID","path:string:远端目录","minimumMb:integer:大文件下限MB");
        add("data_tables","data-management","列出可清理的数据表",false,false,(a,o)->cleanup.tables());
        add("cleanup_preview","data-management","预览数据清理范围和引用保护，返回一次性 token；清理必须先预览",false,false,(a,o)->cleanup.preview(body(a,SshDataCleanupService.Request.class)),"data!:object:{tables:[ssh_script_execution,ssh_script_schedule,ssh_script,ssh_account,ssh_host,ssh_group中的项目],allTime:boolean,from?:ISO8601,to?:ISO8601}");
        add("cleanup_execute","data-management","执行已预览且范围未改变的数据清理，不能撤销",true,true,(a,o)->cleanup.clear(AiJson.text(a,"token")),"token!:string:cleanup_preview返回的token");
        add("configuration_export","data-management","导出配置备份供用户下载。备份内容不回传模型。encrypted=true 时须提供至少8字符密码",true,false,
                (a,o)->files.export(o,configuration.exportAll(optionalText(a,"password"),a.path("encrypted").asBoolean(true))),"encrypted!:boolean:是否加密备份","password:string:加密密码");
        add("configuration_import","data-management","通用配置导入，追加到新分组并暂停定时任务，不覆盖现有数据。data 可为 doumi-backup 备份，或标准快照 {groups:[{id,name,parentId?}],hosts:[{id,groupId,name,host,port}],accounts:[{id,hostId,username,authType,secret:{password?,privateKeyPem?,passphrase?},enabled?}],scripts:[]}；各ID为临时正整数且引用一致。缺少凭据时传secret:{}和enabled:0，明确提示用户补填。不要将第三方密文当作明文密码",true,true,
                (a,o)->{JsonNode data=a.get("data");ConfigurationArchive.Archive archive=data.has("format")?AiJson.JSON.treeToValue(data,ConfigurationArchive.Archive.class):ConfigurationArchive.plain(AiJson.JSON.writeValueAsBytes(data));return configuration.importAll(archive,optionalText(a,"password"));},
                "data!:object:标准配置快照或doumi-backup备份","password:string:加密备份的密码");
    }
    private String token(JsonNode args) { return connections.token(AiJson.id(args,"accountId")); }
    private static Object ok() { return AiJson.object("success",true); }
    private static Long optionalId(JsonNode args,String key) { return args.hasNonNull(key)?AiJson.id(args,key):null; }
    private static String optionalText(JsonNode args,String key) { return args.hasNonNull(key)?args.get(key).asText():null; }
    private static int integer(JsonNode args,String key,int defaultValue,int min,int max) {
        if(!args.has(key))return defaultValue;
        JsonNode value=args.get(key);
        if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<min||value.intValue()>max)throw new IllegalArgumentException(key+" 超出允许范围");
        return value.intValue();
    }
    private static <T> T body(JsonNode args,Class<T> type) { return AiJson.JSON.convertValue(args.get("data"),type); }
    private void add(String name,String page,String description,boolean confirmation,boolean writes,Handler handler,String... fields) {
        Operation op=new Operation();op.name=name;op.page=page;op.description=description;op.confirmation=confirmation;op.writes=writes;op.handler=handler;
        ObjectNode properties=AiJson.JSON.createObjectNode();ArrayNode required=AiJson.JSON.createArrayNode();
        for(String field:fields){String[] parts=field.split(":",3);String key=parts[0].replace("!","");if(parts[0].endsWith("!"))required.add(key);ObjectNode schema=AiJson.object("type",parts[1],"description",parts[2]);properties.set(key,schema);}
        op.parameters=AiJson.object("type","object","properties",properties,"required",required,"additionalProperties",false);operations.put(name,op);
    }
    public JsonNode catalog() {
        ArrayNode result=AiJson.JSON.createArrayNode();
        operations.values().forEach(op->result.add(AiJson.object("name",op.name,"page",op.page,"description",op.description,"requiresConfirmation",op.confirmation,"writes",op.writes,"parameters",op.parameters)));
        return result;
    }
    JsonNode functions() {
        ArrayNode result=AiJson.JSON.createArrayNode();
        operations.values().forEach(op->result.add(AiJson.object("type","function","function",AiJson.object("name",op.name,"description",op.description+(op.confirmation?"。执行前由应用请求用户确认":""),"parameters",op.parameters))));
        return result;
    }
    public JsonNode call(String owner,String name,JsonNode arguments) throws Exception {
        Operation op=require(name,arguments);
        if(!op.confirmation)return result(op,arguments,owner);
        pending.values().removeIf(p->p.expires<System.currentTimeMillis());
        if(pending.size()>=256)throw new IllegalArgumentException("待确认操作过多，请先处理或取消");
        Pending action=new Pending();action.id=UUID.randomUUID().toString();action.owner=owner;action.name=name;action.args=arguments.deepCopy();action.expires=System.currentTimeMillis()+10*60*1000L;pending.put(action.id,action);
        return AiJson.object("requiresConfirmation",true,"actionId",action.id,"name",name,"description",op.description,"arguments",AiJson.mask(arguments),"expiresInSeconds",600);
    }
    public JsonNode confirm(String owner,String id,boolean approved) throws Exception {
        Pending action=pending.get(id);
        if(action==null||!action.owner.equals(owner))throw new IllegalArgumentException("待确认操作不存在或不属于当前会话");
        if(!pending.remove(id,action))throw new IllegalArgumentException("操作已被处理，不能重复执行");
        if(action.expires<System.currentTimeMillis())throw new IllegalArgumentException("操作确认已过期，请重新发起");
        if(!approved)return AiJson.object("cancelled",true,"message","用户拒绝此操作，未执行");
        return result(operations.get(action.name),action.args,owner);
    }
    void discard(String owner) {pending.values().removeIf(p->p.owner.equals(owner));}
    private Operation require(String name,JsonNode args) {
        Operation op=operations.get(name);
        if(op==null)throw new IllegalArgumentException("未开放的 AI 工具");
        if(args==null||!args.isObject()||args.toString().length()>512*1024)throw new IllegalArgumentException("工具参数无效或过大");
        for(JsonNode key:op.parameters.path("required"))if(!args.hasNonNull(key.asText()))throw new IllegalArgumentException("缺少参数 "+key.asText());
        Iterator<Map.Entry<String,JsonNode>> entries=args.fields();
        while(entries.hasNext()){
            Map.Entry<String,JsonNode> e=entries.next();JsonNode schema=op.parameters.path("properties").get(e.getKey());
            if(schema==null)throw new IllegalArgumentException("未知参数 "+e.getKey());
            if(e.getValue().isNull())continue;
            String type=schema.path("type").asText();JsonNode v=e.getValue();
            if((type.equals("string")&&!v.isTextual())||(type.equals("integer")&&!v.isIntegralNumber())||(type.equals("object")&&!v.isObject())||(type.equals("boolean")&&!v.isBoolean()))throw new IllegalArgumentException("参数类型无效："+e.getKey());
        }
        return op;
    }
    private JsonNode result(Operation op,JsonNode args,String owner) throws Exception {
        Object output=op.handler.execute(args,owner);
        return AiJson.object("requiresConfirmation",false,"name",op.name,"changed",op.writes,"result",AiJson.mask(AiJson.JSON.valueToTree(output)));
    }
}
