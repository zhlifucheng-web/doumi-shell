-- ssh_script 增加 work_dir（已建表环境幂等升级）
SET @exist := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ssh_script' AND COLUMN_NAME = 'work_dir'
);
SET @sql = IF(
  @exist = 0,
  'ALTER TABLE ssh_script ADD COLUMN work_dir VARCHAR(512) DEFAULT NULL COMMENT ''执行工作目录，NULL=登录用户家目录'' AFTER content',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
