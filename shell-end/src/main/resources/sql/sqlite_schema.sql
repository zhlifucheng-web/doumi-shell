-- Web Shell 内置 SQLite 库结构（单文件，零外部数据库依赖）
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS ssh_group (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  parent_id INTEGER DEFAULT NULL,
  name TEXT NOT NULL,
  remark TEXT DEFAULT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
  FOREIGN KEY (parent_id) REFERENCES ssh_group (id)
);
CREATE INDEX IF NOT EXISTS idx_ssh_group_parent ON ssh_group (parent_id);

CREATE TABLE IF NOT EXISTS ssh_host (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  group_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  host TEXT NOT NULL,
  port INTEGER NOT NULL DEFAULT 22,
  remark TEXT DEFAULT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
  FOREIGN KEY (group_id) REFERENCES ssh_group (id)
);
CREATE INDEX IF NOT EXISTS idx_ssh_host_group ON ssh_host (group_id);

CREATE TABLE IF NOT EXISTS ssh_account (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  host_id INTEGER NOT NULL,
  username TEXT NOT NULL,
  auth_type TEXT NOT NULL,
  secret_enc BLOB NOT NULL,
  secret_hint TEXT DEFAULT NULL,
  default_cwd TEXT DEFAULT NULL,
  remark TEXT DEFAULT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
  UNIQUE (host_id, username),
  FOREIGN KEY (host_id) REFERENCES ssh_host (id)
);
CREATE INDEX IF NOT EXISTS idx_ssh_account_host ON ssh_account (host_id);

CREATE TABLE IF NOT EXISTS ssh_script (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  host_id INTEGER DEFAULT NULL,
  name TEXT NOT NULL,
  interpreter TEXT NOT NULL DEFAULT 'bash',
  content TEXT NOT NULL,
  work_dir TEXT DEFAULT NULL,
  remark TEXT DEFAULT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
  FOREIGN KEY (host_id) REFERENCES ssh_host (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ssh_script_host ON ssh_script (host_id);

CREATE TABLE IF NOT EXISTS ssh_script_schedule (
  script_id INTEGER PRIMARY KEY,
  account_id INTEGER NOT NULL,
  cron TEXT NOT NULL,
  zone TEXT NOT NULL DEFAULT 'Asia/Shanghai',
  enabled INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
  last_run_at TEXT,
  last_status TEXT,
  last_output TEXT,
  FOREIGN KEY (script_id) REFERENCES ssh_script(id) ON DELETE CASCADE,
  FOREIGN KEY (account_id) REFERENCES ssh_account(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS ssh_script_execution (
 id TEXT PRIMARY KEY, script_id INTEGER, script_name TEXT, account_id INTEGER, host_id INTEGER,
 host_name TEXT, username TEXT, source TEXT NOT NULL, status TEXT NOT NULL,
 started_at TEXT NOT NULL, finished_at TEXT, duration_ms INTEGER, exit_status INTEGER,
 stdout TEXT NOT NULL DEFAULT '', stderr TEXT NOT NULL DEFAULT '', error TEXT, truncated INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_execution_started ON ssh_script_execution(started_at);
CREATE INDEX IF NOT EXISTS idx_execution_script ON ssh_script_execution(script_id,started_at);

CREATE TABLE IF NOT EXISTS ssh_backup_config (
  id INTEGER PRIMARY KEY,
  enabled INTEGER NOT NULL DEFAULT 0,
  target_type TEXT NOT NULL DEFAULT 'MINIO',
  cron TEXT NOT NULL DEFAULT '0 0 2 * * *',
  zone TEXT NOT NULL DEFAULT 'Asia/Shanghai',
  encrypted INTEGER NOT NULL DEFAULT 1,
  minio_endpoint TEXT,
  minio_bucket TEXT,
  minio_access_key TEXT,
  minio_region TEXT,
  minio_path TEXT,
  sftp_host TEXT,
  sftp_port INTEGER DEFAULT 22,
  sftp_username TEXT,
  sftp_auth_type TEXT DEFAULT 'PASSWORD',
  sftp_remote_dir TEXT,
  secret_enc BLOB,
  last_backup_at TEXT,
  last_status TEXT,
  last_message TEXT,
  updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
