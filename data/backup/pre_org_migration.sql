-- MySQL dump 10.13  Distrib 9.4.0, for Win64 (x86_64)
--
-- Host: localhost    Database: tcm_ehr
-- ------------------------------------------------------
-- Server version	9.7.2

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ 'e9b12414-ac6f-11f1-aaf5-966014c6acb6:1-52321';

--
-- Table structure for table `research_groups`
--

DROP TABLE IF EXISTS `research_groups`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `research_groups` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `code` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '组唯一编码，如 NEURO-2026；申请时即校验，防抢占',
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '组显示名，可重复',
  `purpose` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用途说明（可选），供管理员审批判断',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pending' COMMENT 'pending/active/rejected/stopped',
  `applied_by` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '申请人（= 首任组长）',
  `reviewed_by` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  `reject_reason` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '拒绝理由，申请人可见',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `code` (`code`),
  KEY `idx_group_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课题组';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `group_members`
--

DROP TABLE IF EXISTS `group_members`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_members` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `group_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'owner=组长 / member=组员',
  `is_primary` tinyint NOT NULL DEFAULT '1' COMMENT '主组标记，预留一人多组',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_user` (`group_id`,`user_id`),
  KEY `idx_member_user` (`user_id`,`is_primary`) COMMENT '每请求按 user_id 取主组，必须有',
  CONSTRAINT `fk_member_group` FOREIGN KEY (`group_id`) REFERENCES `research_groups` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课题组成员';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `records`
--

DROP TABLE IF EXISTS `records`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `records` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主键UUID',
  `registration_no` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '登记号',
  `outpatient_no` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '门诊号',
  `gender` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '性别',
  `age` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '年龄',
  `visit_count` int DEFAULT NULL COMMENT '就诊次数',
  `western_diagnosis` text COLLATE utf8mb4_unicode_ci COMMENT '西医诊断',
  `tcm_diagnosis` text COLLATE utf8mb4_unicode_ci COMMENT '中医诊断',
  `present_illness` text COLLATE utf8mb4_unicode_ci COMMENT '现病史',
  `chief_complaint` text COLLATE utf8mb4_unicode_ci COMMENT '主诉',
  `self_report` text COLLATE utf8mb4_unicode_ci COMMENT '自诉',
  `inspection` text COLLATE utf8mb4_unicode_ci COMMENT '望诊',
  `pulse` text COLLATE utf8mb4_unicode_ci COMMENT '脉诊',
  `tongue` text COLLATE utf8mb4_unicode_ci COMMENT '舌诊',
  `physical_exam` text COLLATE utf8mb4_unicode_ci COMMENT '查体',
  `pattern` text COLLATE utf8mb4_unicode_ci COMMENT 'è¾¨è¯ç»“è®º/è¯å€™',
  `prescription` text COLLATE utf8mb4_unicode_ci COMMENT '草药',
  `follow_up` text COLLATE utf8mb4_unicode_ci COMMENT '随访',
  `treatment_effect` text COLLATE utf8mb4_unicode_ci COMMENT '治疗效果',
  `department` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '开单科室',
  `doctor_id` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '医生工号',
  `visit_time` datetime DEFAULT NULL COMMENT '接诊时间',
  `structured_data` json DEFAULT NULL COMMENT '结构化数据（NLP抽取结果）',
  `qc_results` json DEFAULT NULL COMMENT '质控检查结果',
  `score` int DEFAULT NULL COMMENT '质控评分（满分100）',
  `grade` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分级：合格/待复核/无效',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态：pending/reviewing/completed/invalid',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `governed` tinyint NOT NULL DEFAULT '0' COMMENT 'å·²æ²»ç†æ ‡è®°',
  `group_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '?????',
  PRIMARY KEY (`id`),
  KEY `idx_registration_no` (`registration_no`),
  KEY `idx_status` (`status`),
  KEY `idx_grade` (`grade`),
  KEY `idx_records_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='病历表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `operation_log`
--

DROP TABLE IF EXISTS `operation_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `operation_log` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'ä¸»é”®UUID',
  `log_time` datetime NOT NULL COMMENT 'æ“ä½œæ—¶é—´',
  `operator` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'æ“ä½œäººç”¨æˆ·å',
  `role` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'æ“ä½œäººè§’è‰²ï¼šç®¡ç†å‘˜/å®¡æ ¸å‘˜',
  `action` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'æ“ä½œç±»åž‹ï¼šæ•°æ®æ¸…æ´—/æ•°æ®é›†å¯¼å‡º/è¯å…¸å¯¼å…¥/è¯å…¸å›žæ»š/äººå·¥å¤æ ¸/æ‰¹é‡é‡ç®—',
  `target` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'æ“ä½œå¯¹è±¡ï¼šç­›é€‰èŒƒå›´/æ–‡ä»¶å/è¯å…¸ç±»åž‹/ç—…åŽ†ID',
  `detail` text COLLATE utf8mb4_unicode_ci COMMENT 'æ“ä½œæ˜Žç»†',
  `group_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '??????????',
  PRIMARY KEY (`id`),
  KEY `idx_log_time` (`log_time`),
  KEY `idx_operator` (`operator`),
  KEY `idx_action` (`action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='æ“ä½œæ—¥å¿—è¡¨';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `review_tasks`
--

DROP TABLE IF EXISTS `review_tasks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `review_tasks` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `record_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态：pending/completed',
  `issue_type` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '问题类型（缺失字段/逻辑冲突/评分不达标）',
  `score` int DEFAULT NULL COMMENT '当前评分',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `deadline_time` datetime DEFAULT NULL COMMENT '复核截止时间（创建时间+7个工作日）',
  `reviewed_by` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '复核人用户名',
  `completed_time` datetime DEFAULT NULL COMMENT '复核完成时间',
  `is_obsolete` tinyint NOT NULL DEFAULT '0' COMMENT '作废标记',
  `group_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '?????',
  PRIMARY KEY (`id`),
  KEY `idx_record_id` (`record_id`),
  KEY `idx_obsolete` (`is_obsolete`),
  KEY `idx_review_task_group` (`group_id`),
  CONSTRAINT `fk_review_record` FOREIGN KEY (`record_id`) REFERENCES `records` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复核任务表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色：管理员/审核员',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pending' COMMENT 'pending=???????? / active=???? / disabled=??',
  `has_pending_group` tinyint NOT NULL DEFAULT '0' COMMENT '1=????????????????',
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `nlp_task`
--

DROP TABLE IF EXISTS `nlp_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `nlp_task` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务ID(UUID)',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态：QUEUED/RUNNING/COMPLETED/CANCELLED/INTERRUPTED/FAILED',
  `total` int NOT NULL DEFAULT '0' COMMENT '计划处理条数',
  `done` int NOT NULL DEFAULT '0' COMMENT '已处理条数',
  `success` int NOT NULL DEFAULT '0' COMMENT '成功条数',
  `failed` int NOT NULL DEFAULT '0' COMMENT '失败条数',
  `current_label` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '当前处理的病历标识',
  `filters_json` text COLLATE utf8mb4_unicode_ci COMMENT '筛选范围(JSON)',
  `created_by` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提交人用户名',
  `failure_list` json DEFAULT NULL COMMENT '失败清单(仅存前500条)',
  `failure_truncated` tinyint NOT NULL DEFAULT '0' COMMENT '失败清单是否被截断',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `started_at` datetime DEFAULT NULL COMMENT '开始时间',
  `finished_at` datetime DEFAULT NULL COMMENT '结束时间',
  `group_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '??????',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_nlp_task_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NLP批量解析任务表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qc_task`
--

DROP TABLE IF EXISTS `qc_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qc_task` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务ID(UUID)',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态：QUEUED/RUNNING/COMPLETED/CANCELLED/INTERRUPTED/FAILED',
  `total` int NOT NULL DEFAULT '0' COMMENT '计划处理条数',
  `done` int NOT NULL DEFAULT '0' COMMENT '已处理条数',
  `success` int NOT NULL DEFAULT '0' COMMENT '成功条数',
  `failed` int NOT NULL DEFAULT '0' COMMENT '失败条数',
  `qualified` int NOT NULL DEFAULT '0' COMMENT '合格数',
  `pending_review` int NOT NULL DEFAULT '0' COMMENT '待复核数',
  `invalid` int NOT NULL DEFAULT '0' COMMENT '无效数',
  `current_label` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '当前处理的病历标识',
  `filters_json` text COLLATE utf8mb4_unicode_ci COMMENT '筛选范围(JSON)',
  `created_by` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提交人用户名',
  `role` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提交时角色快照，供 worker 重建 RecordFilter',
  `failure_list` json DEFAULT NULL COMMENT '失败清单(仅存前500条)',
  `failure_truncated` tinyint NOT NULL DEFAULT '0' COMMENT '失败清单是否被截断',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `started_at` datetime DEFAULT NULL COMMENT '开始时间',
  `finished_at` datetime DEFAULT NULL COMMENT '结束时间',
  `group_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '?????????? worker ?? RecordFilter',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_qc_task_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质控批量重算任务表';
/*!40101 SET character_set_client = @saved_cs_client */;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-30 21:24:51
