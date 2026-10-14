# Flyway 迁移目录

## 采用方式：基线接管（baseline），不搬运现有表结构

本项目的**全量建库脚本**仍是仓库根目录的 `database-init.sql`（含
`CREATE DATABASE` / `USE`），全新环境手工执行一次即可。这一点**不变**，以保留
你们既有的初始化流程。

Flyway 从这里（`classpath:db/migration`）开始**接管后续 schema 变更**：

- 现有库（表已由 `database-init.sql` 建好、没有 Flyway 历史表）在首次启动时被
  **标记为基线版本 1**（`baseline-on-migrate=true` + `baseline-version=1`），
  不会重复执行任何历史脚本。
- 之后每一次 schema 变更，新增一个**版本化脚本**，应用启动时自动执行、并在
  `flyway_schema_history` 表留痕。

> 为什么不把现有表结构搬成一个 `V1__init_schema.sql`：那样会和 `database-init.sql`
> 形成**两份会各自漂移的全量表结构**。基线接管的做法只保留一份权威来源
> （`database-init.sql`），Flyway 只负责"基线之后的增量"。

## 命名规范

```
V<版本>__<描述>.sql        # 版本化迁移，按版本号升序执行，只执行一次
R__<描述>.sql              # 可重复迁移，内容变化即重跑（用于视图/存储过程等）
```

示例（未来加列时）：

```
V2__add_records_xxx_column.sql
V3__create_xxx_table.sql
```

## 写脚本的注意事项

- 目录里的脚本由 Flyway 对**连接串里的库**（`tcm_ehr`）执行，**不要写**
  `CREATE DATABASE` / `USE` —— 那是连接前提，不是 schema 版本。
- 单次变更尽量小且可重入判断清晰；跨版本引用只允许引用更早版本已建立的对象。
- 脚本一旦提交并被环境执行过，**不要再改内容**（校验和会变，Flyway 会拒绝启动）。
  要修就新增一个更高版本的脚本。
- 新增/修改实体字段时，别忘了同时更新 `database-init.sql`（全量脚本），
  两者会由 `EntityDdlDriftGuardTest` 等守卫测试协助发现漂移。

## 与测试的关系

测试不启动 Spring 全量上下文（无 `@SpringBootTest`），因此**不会触发 Flyway**；
集成测试走 `src/test/resources/schema-h2.sql`（H2 精简 schema）。Flyway 迁移是
MySQL 语法，不要指望它能在 H2 上跑。
