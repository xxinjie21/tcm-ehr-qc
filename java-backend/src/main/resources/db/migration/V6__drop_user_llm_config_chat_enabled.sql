-- V6：删除 user_llm_config.chat_enabled —— 「AI 助手问答」这条竖切已整体移除。
--
-- 背景：悬浮球助手（AiAssistant.vue → stores/ai.js → POST /api/ai/chat → AiServiceImpl.chat）
-- 整条链路已删，AiFeature.CHAT 枚举值、LlmProperties.chatEnabled、DTO/VO/PO 字段与
-- 前端 LlmConfigDialog 的开关一并移除。该列随之失去读写方，留着只会让
-- 「字段存在但无人使用」的假象继续误导后续维护。
--
-- 影响面：仅 user_llm_config 一列。其余三个功能开关（interpret / review / term_suggest）
-- 与「启用 LLM」总开关不受影响。
--
-- 幂等：全新库由 database-init.sql 直接建成（已不含该列），故本脚本需判存在性。
-- MySQL 8 不支持 DROP COLUMN IF EXISTS，沿用 V2/V4/V5 的 information_schema 守卫写法。
--
-- 回退：Flyway 已执行的版本不能改内容、也不能删脚本。要回退请另写 V7，
-- 重新 ADD COLUMN chat_enabled TINYINT NOT NULL DEFAULT 1。
--
-- 注意：本列有 NOT NULL DEFAULT 1，删除后不可恢复原值；但该列只承载「是否单独用 LLM」
-- 这一个开关，助手功能已不存在，原值无保留价值。

-- 删列（若不存在则空操作）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_llm_config'
      AND COLUMN_NAME = 'chat_enabled');
SET @s := IF(@c = 1,
    'ALTER TABLE user_llm_config DROP COLUMN chat_enabled',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
