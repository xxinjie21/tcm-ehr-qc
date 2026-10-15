-- V3：user_llm_config 增加四个「分 AI 功能」的局部开关。
-- 总开关（enabled）原本决定所有 AI 功能是否走 LLM；这四个列让每项功能单独控制，
-- 总开关保持不动。默认值 1 —— 未单独设置的用户行为与升级前完全一致。

-- user_llm_config.interpret_enabled
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_llm_config' AND COLUMN_NAME = 'interpret_enabled');
SET @s := IF(@c = 0,
    'ALTER TABLE user_llm_config ADD COLUMN interpret_enabled TINYINT NOT NULL DEFAULT 1 COMMENT ''AI 解读是否单独用 LLM''',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- user_llm_config.chat_enabled
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_llm_config' AND COLUMN_NAME = 'chat_enabled');
SET @s := IF(@c = 0,
    'ALTER TABLE user_llm_config ADD COLUMN chat_enabled TINYINT NOT NULL DEFAULT 1 COMMENT ''AI 助手问答是否单独用 LLM''',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- user_llm_config.review_enabled
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_llm_config' AND COLUMN_NAME = 'review_enabled');
SET @s := IF(@c = 0,
    'ALTER TABLE user_llm_config ADD COLUMN review_enabled TINYINT NOT NULL DEFAULT 1 COMMENT ''复核意见是否单独用 LLM''',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- user_llm_config.term_suggest_enabled
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_llm_config' AND COLUMN_NAME = 'term_suggest_enabled');
SET @s := IF(@c = 0,
    'ALTER TABLE user_llm_config ADD COLUMN term_suggest_enabled TINYINT NOT NULL DEFAULT 1 COMMENT ''术语建议是否单独用 LLM''',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
