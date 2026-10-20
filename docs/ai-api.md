# AI 操作接口

接口基础路径为 `/api/ssh-console/ai`，沿用本应用的同源访问方式及业务服务。与管理页面一样，仅向可信网络开放；需要跨网络使用时，在部署入口配置认证和 HTTPS。

除下载外，响应沿用 `{code:200,success:true,data:...}`，失败时 `success:false`，应检查业务状态。配置及对话接口返回 `Cache-Control: no-store`。内置对话采用 OpenAI 兼容 Chat Completions 的 `messages/tools/tool_calls/tool_call_id` 协议，模型需支持工具调用。前端请求厂商的流式 Chat Completions（`stream: true`），将文本增量通过应用内 SSE 事件推送；工具调用参数会先完整合并和校验，再进入确认或执行流程。若厂商忽略流式选项并返回单个 JSON 响应，也会兼容处理。连接中断或输出未正常结束时，已接收文本保留为不完整状态，未完成工具调用不会执行。

## 工具 API

| 请求 | 用途 |
| --- | --- |
| `GET /tools` | 获取工具名称、页面、说明、参数 JSON Schema、是否写入及是否需要确认 |
| `POST /tools/call` | 请求 `{name,arguments,conversationId?}`，省略 conversationId 时为外部客户端公共 `api` 工作域 |
| `POST /tools/confirm` | `{actionId,approved,conversationId?}`，执行或拒绝原操作，不能修改原参数 |

只读操作以及创建分组/主机/账户直接执行；命令、写文件、修改/删除、脚本执行、导入导出、清理等返回 `requiresConfirmation:true` 和 `actionId`。外部 AI 客户端必须把具体参数展示给操作者，再由操作者确认；不要自动确认模型提出的动作。待确认参数保存在服务端，确认时限 600 秒，绑定工作域、单次消费；过期、跨域、重复确认都会失败。多个外部客户端需要隔离时，应各自创建 conversationId 并在调用中携带。

直接执行或确认后返回 `{requiresConfirmation:false,name,changed,result}`；拒绝返回 `{cancelled:true,message}`。输出中的结构化密码、私钥和密钥被遮蔽。远端文本、命令输出和附件原文仍可能包含敏感信息，调用方应按实际需要读取。

示例：

```http
POST /api/ssh-console/ai/tools/call
Content-Type: application/json

{"name":"tree_children","arguments":{"parentType":"ROOT"}}
```

```http
POST /api/ssh-console/ai/tools/call
Content-Type: application/json

{"name":"ssh_command","arguments":{"accountId":12,"command":"uname -a","timeoutSeconds":30}}
```

第二个调用取得 `actionId` 后，展示参数并取得操作者确认，再提交：

```json
{"actionId":"返回的UUID","approved":true}
```

## 五个页面的工具

准确参数以 `GET /tools` 返回的 JSON Schema 与说明为准。账户 ID 可从主机下的 `tree_children` 或 `schedule_accounts` 查询，不能把主机 ID 当作账户 ID。

| 页面 | 工具 |
| --- | --- |
| 终端与监控 | `tree_children`、`group_get/create/update/move/delete`、`host_get/create/update/move/delete`、`account_get/create/update/delete/test`、`ssh_connect/disconnect/command`、`host_metrics` |
| 终端内的文件管理 | `file_list/directories/read/write/upload/mkdir/rename/delete/download`、`folder_tree` |
| 脚本与定时任务 | `scripts_list`、`script_get/create/update/delete/run`、`schedule_accounts`、`cron_preview`、`executions_list`、`execution_get/stop` |
| 进程 | `processes_list`、`process_kill` |
| 存储管理 | `disk_scan`，结合文件工具处理选中的文件 |
| 数据管理 | `data_tables`、`cleanup_preview/execute`、`configuration_export/import`、`attachment_read` |

表内 `/` 为名称简写，例如 `group_get/create` 对应 `group_get`、`group_create`。AI 自有 SSH 连接与页面终端分开；`ssh_disconnect` 仅关闭 AI 自有连接。清理需要先调用 `cleanup_preview` 再使用其 token，执行前仍需确认。下载返回应用内 `downloadUrl`，供用户点击保存；导出的备份内容不回传模型。

## 厂商与对话 API

| 请求 | 参数及结果 |
| --- | --- |
| `GET /providers` | 配置列表，仅含 hasApiKey，不返回 Key |
| `POST /providers` | `{id?,name,baseUrl,model,apiKey?,clearApiKey?}`；编辑时空 Key 保留旧值 |
| `DELETE /providers/{id}` | 删除配置 |
| `POST /providers/{id}/test` | 使用已保存配置请求一次普通回复；不验证完整工具能力 |
| `POST /conversations` | `{providerId,authorizationMode?,server?}`，模式为 `ask`（默认）或 `auto`；server 可带 accountId、hostId、name、address、port、username，用于按服务器筛选与恢复历史 → `{id,providerId,authorizationMode}` |
| `GET /conversations/history?accountId=...` | 最近 100 条会话摘要；省略 accountId 时返回全部服务器会话。摘要含 title、server metadata、state、createdAt、updatedAt |
| `GET /conversations/latest?accountId=...&hostId=...` | 返回指定账户（或无账户时指定主机）的最近会话摘要；都省略时返回未绑定主机的最近会话 |
| `POST /conversations/{id}/authorization` | `{mode:"ask"|"auto"}`；仅空闲且无待确认操作时可切换。`auto` 会执行本会话中所有要求确认的工具操作 |
| `POST /conversations/{id}/provider` | `{providerId}`；切换到已配置的厂商/模型并保留会话历史，仅空闲且无待确认操作时可切换 |
| `GET /conversations/{id}?since=0` | 返回 authorizationMode、providerId、state、running、cursor、revision、events、pending；since 为上次 cursor |
| `POST /conversations/{id}/messages` | `{text,workspace?,attachmentIds:[]}`，异步开始，随后轮询状态 |
| `POST /conversations/{id}/approve` | `{actionIds:[...],approved:true或false}`，处理待确认操作并继续对话 |
| `POST /conversations/{id}/stop` | 停止后续调用，等待当前请求结束；不撤回已启动的远端操作 |
| `DELETE /conversations/{id}` | 清除未运行的会话、附件及下载链接 |
| `POST /conversations/{id}/attachments` | multipart：多项 files，paths 为等长 JSON 字符串数组；使用相对路径，返回附件 id/name/size/text |
| `GET /downloads/{id}` | 原生文件响应，链接 2 小时后失效，清除所属会话也会失效 |

状态为 idle/running/awaiting_confirmation/stopping/error。轮询中按 `callId` 更新同一工具卡片，按 `sequence` 去重。事件角色为 user/assistant/tool/error/notice；自动授权的工具事件 state 为 `auto_authorized`。`revision` 在成功修改数据时增加，可用于刷新相关页面；`tool.changed` 标识该操作修改了数据。自动授权只对明确启用它的单个会话有效，API 客户端应在启用前向操作者说明其影响范围。

会话消息和事件以应用凭据主密钥加密保存在 SQLite，页面刷新和服务重启后可通过历史列表读取。服务重启时 running、stopping 或 awaiting_confirmation 会话会恢复为中断状态；待确认操作不会在重启后自动执行。附件原文件、下载链接和未完成附件不持久化，历史中保存的附件消息内容仍可查看，但重启后不能重新读取附件。数据管理的清理表包含“AI 会话历史”；活动中及等待确认的会话受到保护。厂商配置也单独保存于 SQLite，不随普通服务器配置备份导出。

## 通用配置导入

附件可以保留原始目录，模型读取后转换为标准快照，再调用 `configuration_import`。标准快照形状如下，各 ID 是本次快照内的临时引用，导入时自动重映射：

```json
{
  "groups": [{"id":1,"name":"导入的目录"},{"id":2,"name":"子目录","parentId":1}],
  "hosts": [{"id":3,"groupId":2,"name":"示例","host":"192.0.2.10","port":22}],
  "accounts": [{"id":4,"hostId":3,"username":"demo","authType":"PASSWORD","secret":{},"enabled":0}],
  "scripts": []
}
```

调用参数为 `{data:上述快照}`；也可传现有 `doumi-backup` 格式及可选 password。缺失凭据时使用空 secret 和 enabled=0，并提示用户补填。不能将未知第三方密文作为明文密码。根分组加导入标记、不会覆盖已有配置；支持父子目录乱序输入，引用缺失、循环或重复会回滚，导入的定时任务会暂停。

附件单文件 20 MB、会话合计 50 MB、服务端合计 200 MB。文本片段按 UTF-8 提供，二进制仅提供元信息；`attachment_read` 可按字符偏移分段读取前 2 MB 字符范围。远端 `file_read` 读取普通文本文件的前最多 64 KB，`file_write` 最多 1 MB，大文件使用原文件管理功能。命令最多 60 秒，超时也不保证远端命令未生效。
