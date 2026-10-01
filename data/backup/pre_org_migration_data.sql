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
-- Dumping data for table `research_groups`
--

LOCK TABLES `research_groups` WRITE;
/*!40000 ALTER TABLE `research_groups` DISABLE KEYS */;
INSERT INTO `research_groups` VALUES ('44e38226-ead6-4d16-970f-90871b3d03e7','XXJ','HHH',NULL,'active','3120459863f4ea76c087356efd2d66e9','admin','2026-09-29 01:02:59',NULL,'2026-09-29 00:56:27'),('grp-default-2026','DEFAULT-2026','默认课题组','存量数据归集','active',NULL,NULL,NULL,NULL,'2026-09-28 23:46:46');
/*!40000 ALTER TABLE `research_groups` ENABLE KEYS */;
UNLOCK TABLES;

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
-- Dumping data for table `group_members`
--

LOCK TABLES `group_members` WRITE;
/*!40000 ALTER TABLE `group_members` DISABLE KEYS */;
INSERT INTO `group_members` VALUES ('9ff79e77-b032-45ff-840e-a6f8fc4da9bf','44e38226-ead6-4d16-970f-90871b3d03e7','3120459863f4ea76c087356efd2d66e9','owner',1,'2026-09-29 00:56:27'),('gm-admin-0001','grp-default-2026','admin-0001','member',1,'2026-09-28 23:46:46'),('gm-audit-0001','grp-default-2026','audit-0001','owner',1,'2026-09-28 23:46:46');
/*!40000 ALTER TABLE `group_members` ENABLE KEYS */;
UNLOCK TABLES;

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
-- Dumping data for table `review_tasks`
--

LOCK TABLES `review_tasks` WRITE;
/*!40000 ALTER TABLE `review_tasks` DISABLE KEYS */;
INSERT INTO `review_tasks` VALUES ('0500dca5a79f65b0da1cf0a35bcdfd51','e1e0cc77-c805-4f10-af39-19a3f8f9c5ad','pending','缺失字段',89,'2026-09-29 12:11:41','2026-10-08 12:11:41',NULL,NULL,0,'grp-default-2026'),('1fe666da6389e8624e4ed38a524802fc','968574bb-7ff2-4185-84c1-7a5b542d2a1f','pending','缺失字段',89,'2026-09-29 12:11:44','2026-10-08 12:11:44',NULL,NULL,0,'grp-default-2026'),('2b41b216c36a28a100b563bc9c6851a8','d4c44d2c-fe53-4b8d-a1cf-d0339d8e9ec1','pending','缺失字段',89,'2026-09-29 12:11:44','2026-10-08 12:11:44',NULL,NULL,0,'grp-default-2026'),('338f9a73f800d84ae0fe56344713f028','3e1811e9-9c7c-4316-b1f2-5f5bebfd7e24','pending','缺失字段',89,'2026-09-29 12:11:44','2026-10-08 12:11:44',NULL,NULL,0,'grp-default-2026'),('33fb81ebdcb648a55c1f991e409b9837','f7cc8d76-4e96-4d6e-9765-88e927634aeb','pending','缺失字段',89,'2026-09-29 12:11:41','2026-10-08 12:11:41',NULL,NULL,0,'grp-default-2026'),('35b54b03d897adb96e974431f91e90e7','2ba42bfb-f371-478d-bb9d-90f5300905aa','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('3708d08faae52b22b7d6da3f63d26472','5cec1bc7-0c62-4248-9f07-b88dd0e5f918','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('378b0be069a9c8c364d89863d6c467b2','d721cb79-738b-4de1-9602-f6ca27af8a37','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('37ea2a02d73e2d75fd29546d54f39f3d','1a7950c2-6e39-40f8-b74d-f7889852c52f','pending','缺失字段',89,'2026-09-29 12:11:41','2026-10-08 12:11:41',NULL,NULL,0,'grp-default-2026'),('47ff743e8af2729852af0a8124c60ed1','6205fd97-1b81-477c-b87b-5ddc98e9f3cb','pending','缺失字段',89,'2026-09-29 12:11:41','2026-10-08 12:11:41',NULL,NULL,0,'grp-default-2026'),('4c74f817a28f771b74196481168cf38d','b16ec248-872d-4caf-bb04-21ce968541bb','pending','缺失字段',89,'2026-09-29 12:11:44','2026-10-08 12:11:44',NULL,NULL,0,'grp-default-2026'),('51def45d775ec6075dd5426b246c512b','470694f8-9dc8-456a-829a-22810d9a0663','pending','缺失字段',89,'2026-09-29 12:11:41','2026-10-08 12:11:41',NULL,NULL,0,'grp-default-2026'),('5f7a81b5a5b094380d65ce945eba0775','426778f4-e9ff-4706-80fa-3343a46a0882','pending','缺失字段',89,'2026-09-29 12:11:43','2026-10-08 12:11:43',NULL,NULL,0,'grp-default-2026'),('630ccbae311f6794cc527c65a3085953','7d507be6-b7f4-4ca7-a7c0-3d811f9d33f8','pending','缺失字段',89,'2026-09-29 12:11:41','2026-10-08 12:11:41',NULL,NULL,0,'grp-default-2026'),('6849deaad6b49c3f78f49fa6f5b0cd41','34e3b650-1774-4527-9f23-8513fac07af3','pending','缺失字段',89,'2026-09-29 12:11:43','2026-10-08 12:11:43',NULL,NULL,0,'grp-default-2026'),('684ed9c201ac7a35292aa0289be5b430','00b14068-41ed-4f71-a5d6-bf0f29034537','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('6bc96d78ba3c30861596a6948dbdc43d','a0d07267-013d-4bfe-bc31-272643be1804','pending','缺失字段',89,'2026-09-29 12:11:41','2026-10-08 12:11:41',NULL,NULL,0,'grp-default-2026'),('705c07e21509230848d38ff527f67b71','67e59509-50c2-4d76-a713-870f5494297a','pending','缺失字段',89,'2026-09-29 12:11:43','2026-10-08 12:11:43',NULL,NULL,0,'grp-default-2026'),('7067733da3571cd1112f6d9e9ee326e9','c7b85d4b-8798-45ab-a28e-ad735b5280f6','pending','缺失字段',89,'2026-09-29 12:11:44','2026-10-08 12:11:44',NULL,NULL,0,'grp-default-2026'),('725a4f4be43fc27777ec7d0ad5003e70','0d5e29a1-cfea-47da-b407-71f28d3848d1','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('738addfae617198f4076123c8bab6bf0','13f60db5-1eaa-4a7b-b4ab-56c259b57cd5','pending','缺失字段',89,'2026-09-29 12:11:44','2026-10-08 12:11:44',NULL,NULL,0,'grp-default-2026'),('86deebfebbfe2728a510aa6189ff8d0d','cd691dee-e567-4b97-97f8-46551ed29d25','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('86eb5c3d1f315b4ee8087d0020e0eb2e','0f547167-f6e7-4640-b4d6-11c37fedb92b','pending','缺失字段',89,'2026-09-29 12:11:43','2026-10-08 12:11:43',NULL,NULL,0,'grp-default-2026'),('8ae35beb1a619a9c163bd7dfe2175863','39f64ece-4139-4fb2-8d5f-149c04c11633','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('8da23d82caa4099b1cbc54f2cff209d3','aba96476-7c36-4713-b4cd-1295b2459f1f','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('8f84df09267d08e841df7fafd1209591','d088f855-43e4-4776-b17c-971c0690d492','pending','缺失字段',89,'2026-09-29 12:11:41','2026-10-08 12:11:41',NULL,NULL,0,'grp-default-2026'),('8fad8fd4b717e7fcf300f122c1711475','aa34ecb5-a0f0-4718-a4da-3e35f2bb9189','pending','缺失字段',89,'2026-09-29 12:11:44','2026-10-08 12:11:44',NULL,NULL,0,'grp-default-2026'),('9c0071589abb64eb8e66b623d8abb599','d8355f62-ed97-454e-b111-7e936f383765','pending','缺失字段',89,'2026-09-29 12:11:43','2026-10-08 12:11:43',NULL,NULL,0,'grp-default-2026'),('9f075da5561a6d6843db7d37794e681d','df985383-c944-4db9-971a-48533658fe7a','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('a70efa30a590498cc5137161f426e8e7','49e3f9ff-d290-49c2-9101-294ba4861cbf','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('aa186a2d7ab950d24723bc0a76c6c65a','7ec698e9-a3f8-4dd1-8233-48f11a18919f','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('b64dad77324d6964c89319d0a7c6ac53','4487356e-15a5-41dc-b937-3bcb71fe4b62','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('b686edfbfc508b5efae79de2341061d1','87d8d955-6212-40af-8eaa-12ccef0a9c0f','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('b794cce26ad18bff5325aa1aa2328f81','d8faef12-4473-43f4-9d92-97d7f966b4ab','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('b836b31e95e053709e77e60bf872646e','f66b8bb2-73fb-442e-b0b0-1c32033a36fb','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('c4e772c1c311c27805435d7fbc20ffbf','baddd9c9-95ae-4bbc-8acf-a2c3f57b2120','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('c772e39abad1fd7c971dcce5a5be98f7','c6788d10-d62f-4bf4-8dcf-d002c034a708','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('c98b29d4e564db0f9f81a6b0a5778a27','dd038b9b-2446-4227-886b-559a77c0c38c','pending','缺失字段',89,'2026-09-29 12:11:44','2026-10-08 12:11:44',NULL,NULL,0,'grp-default-2026'),('cbd20c46db4ffb38b84d91cf16117fc3','6a9a2206-9a45-4abc-8c9c-0184467ea80e','pending','缺失字段',89,'2026-09-29 12:11:41','2026-10-08 12:11:41',NULL,NULL,0,'grp-default-2026'),('d307c3c76d16ee1d0a69ebb7eefa352e','20f01392-72d0-4b42-95d6-0b4787d3ead7','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('deac9c52fa322080671b2056f6922caa','44057949-edfc-40a4-ad9b-b8171a6c1e52','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('dee1a3e0cdc0fe772d658658388a1307','8cc76f0a-7d14-485a-a537-0e7f03d3fbe1','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('dfcd24e8a22edfa9815a7bc9e488356b','735813e6-aada-432d-9fc0-a3adaf3d65dc','pending','缺失字段',89,'2026-09-29 12:11:43','2026-10-08 12:11:43',NULL,NULL,0,'grp-default-2026'),('e1c76bc47eda13a0a8730a65c2dab431','61b8ec9c-4f58-4df8-ad69-fd2f2ef85f5d','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('ec6365c049b2dff3185f9e9e17717647','3b20a359-774e-4372-b5e4-4899986faea7','pending','缺失字段',89,'2026-09-29 12:11:44','2026-10-08 12:11:44',NULL,NULL,0,'grp-default-2026'),('f600ca6515d22b34fa6303825798f922','8b398b77-4124-4af3-aa92-bb93bc5775c0','pending','缺失字段',89,'2026-09-29 12:11:44','2026-10-08 12:11:44',NULL,NULL,0,'grp-default-2026'),('f7d64d809a7fc9a5a97ae2d6b7857fbb','a44fc104-465c-430c-bbd5-2e872883e4a7','pending','缺失字段',89,'2026-09-29 12:11:41','2026-10-08 12:11:41',NULL,NULL,0,'grp-default-2026'),('fc7a3ec7b694e642659d2da42c3f3cb3','62509839-f6e0-49da-b26f-e9087e44314c','pending','缺失字段',89,'2026-09-29 12:11:42','2026-10-08 12:11:42',NULL,NULL,0,'grp-default-2026'),('fdda316f4e2008015662636b45a7b08b','50fc072a-28a2-4f38-8952-624f6129395f','pending','缺失字段',89,'2026-09-29 12:11:41','2026-10-08 12:11:41',NULL,NULL,0,'grp-default-2026'),('fe013aaaf958d16b44671a1c75f2b13f','2341184c-7175-457d-8484-cf4760bc83ea','pending','缺失字段',89,'2026-09-29 12:11:44','2026-10-08 12:11:44',NULL,NULL,0,'grp-default-2026');
/*!40000 ALTER TABLE `review_tasks` ENABLE KEYS */;
UNLOCK TABLES;

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
-- Dumping data for table `users`
--

LOCK TABLES `users` WRITE;
/*!40000 ALTER TABLE `users` DISABLE KEYS */;
INSERT INTO `users` VALUES ('3120459863f4ea76c087356efd2d66e9','XXJ','$2a$10$gjotEkypy.033epSlAZaouTFtZ.I//mCNcBEhQ3OkxfhgL8jeLUuG','用户','2026-09-29 00:56:27','active',0),('admin-0001','admin','$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi','管理员','2026-09-10 01:03:21','active',0),('audit-0001','auditor','$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi','审核员','2026-09-10 01:03:21','active',0);
/*!40000 ALTER TABLE `users` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-30 21:25:33
