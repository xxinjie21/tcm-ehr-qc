package com.tcm.ehr.common.utils;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;

/**
 * 批量插入通道（P2-8）：把「循环内逐条 insert」换成批处理会话。
 *
 * <p><b>为什么需要它</b>：逐条 insert 每行一次往返；整份词典（疾病类上千条）
 * 或一次 500 条的导入，就是上千次往返。走 ExecutorType.BATCH 会话后每
 * {@value #BATCH_SIZE} 条 flush 一次，配合 JDBC 的 {@code rewriteBatchedStatements=true}
 * 合并成多值 INSERT，往返数从「条数」降到「条数 / 500」。</p>
 *
 * <p><b>批写机制已交给 MyBatis-Plus 官方 {@link SqlHelper#executeBatch}</b>
 * （《自研组件替换计划》#11）：原实现自己 {@code openSession(BATCH, false)}、
 * 手写「每 N 条 flush 一次 + 补尾批 + commit」，属重复实现框架已提供的能力。
 * 本类只保留两件框架没有的东西 —— ① 统一的 {@value #BATCH_SIZE} 口径与调用签名；
 * ② <b>sqlSessionFactory 为 null 时退化为逐条写</b>（单测直接 {@code new} 服务类、
 * 未走 Spring 装配时的既有契约，BatchInsertTransactionTest 钉着）。</p>
 *
 * <p><b>事务语义（关键，不要改）</b>：SqlHelper.executeBatch 取到的会话由
 * SpringManagedTransactionFactory 装配，连接取自 DataSourceUtils.getConnection
 * —— 即<b>当前事务的连接</b>。因此：</p>
 * <ul>
 *   <li>写入落在调用方的事务里，随外层事务一起提交 / 回滚；</li>
 *   <li>{@code SpringManagedTransaction.commit()} / {@code rollback()} 在连接是
 *       事务连接时直接返回（不真正提交 / 回滚），所以框架内部的 {@code commit()} /
 *       {@code rollback()} 在事务内都是空操作，在事务外才是真动作 —— 两种上下文都对；</li>
 *   <li>异常一律向上抛，<b>不做逐条重试</b>：唯一键冲突等错误必须让外层事务回滚，
 *       与改造前「第 k 条插入即抛」的原子性一致，不能被吞掉。</li>
 * </ul>
 */
public final class BatchInsert {

    /**
     * 每批攒多少条 flush 一次。
     *
     * <p>与 P1-4 的批写口径（NlpBatchServiceImpl.WRITE_BATCH_SIZE）取同一个值：
     * 500 条一次 flush 在「往返数」与「单批内存 / 语句长度」之间是既有实践验证过的折中。</p>
     */
    public static final int BATCH_SIZE = 500;

    private static final Log LOG = LogFactory.getLog(BatchInsert.class);

    private BatchInsert() {
    }

    /**
     * 批量插入。
     *
     * @param sqlSessionFactory 批写通道；{@code null} 时退化为逐条写
     * @param mapperType        批写会话里要取的 Mapper 类型（会话与 Mapper 必须同源，
     *                          不能把普通 Mapper 代理塞进批会话）
     * @param mapper            普通 Mapper 代理，仅用于「未装配批写通道」的退化路径
     * @param rows              待插入实体；为空时直接返回
     * @param <T>               实体类型
     * @param <M>               Mapper 类型
     */
    public static <T, M extends BaseMapper<T>> void insert(SqlSessionFactory sqlSessionFactory,
                                                           Class<M> mapperType,
                                                           BaseMapper<T> mapper,
                                                           List<T> rows) {
        // 1. 空集直接返回：批会话不必建，也避免下游对空表做无谓的 flush
        if (rows == null || rows.isEmpty()) {
            return;
        }
        // 2. 未装配批写通道（单测 new）：逐条写，与改造前行为一致
        if (sqlSessionFactory == null) {
            for (T row : rows) {
                mapper.insert(row);
            }
            return;
        }
        // 3. 批写：会话与 Mapper 同源取自本会话，每 BATCH_SIZE 条 flush 一次 —— 见类注释。
        SqlHelper.executeBatch(sqlSessionFactory, LOG, rows, BATCH_SIZE,
                (session, row) -> session.getMapper(mapperType).insert(row));
    }
}
