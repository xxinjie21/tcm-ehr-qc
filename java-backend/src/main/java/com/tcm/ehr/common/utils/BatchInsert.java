package com.tcm.ehr.common.utils;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;

/**
 * 批量插入通道（P2-8）：把「循环内逐条 {@code insert}」换成批处理会话。
 *
 * <p><b>为什么需要它</b>：逐条 {@code insert} 每行一次往返；整份词典（疾病类上千条）
 * 或一次 500 条的导入，就是上千次往返。走 {@code ExecutorType.BATCH} 会话后每
 * {@value #BATCH_SIZE} 条 flush 一次，配合 JDBC 的 {@code rewriteBatchedStatements=true}
 * 合并成多值 INSERT，往返数从「条数」降到「条数 / 500」。</p>
 *
 * <p><b>事务语义（关键，不要改）</b>：{@code SqlSessionFactory.openSession(BATCH, false)}
 * 建出的会话，其事务由 {@code SpringManagedTransactionFactory} 装配，连接取自
 * {@code DataSourceUtils.getConnection} —— 即<b>当前事务的连接</b>。因此：</p>
 * <ul>
 *   <li>写入落在调用方的事务里，随外层事务一起提交 / 回滚；</li>
 *   <li>{@code SpringManagedTransaction.commit()} / {@code rollback()} 在连接是
 *       事务连接时直接返回（不真正提交），所以这里的 {@code commit()} 在事务内是空操作，
 *       在事务外才是真提交 —— 两种上下文都对；</li>
 *   <li>异常一律向上抛，<b>不做逐条重试</b>：唯一键冲突等错误必须让外层事务回滚，
 *       与改造前「第 k 条插入即抛」的原子性一致，不能被吞掉。</li>
 * </ul>
 *
 * <p><b>可选装配</b>：{@code sqlSessionFactory} 为 {@code null}（单测直接 {@code new}
 * 服务类、未走 Spring 装配）时退化为逐条写，语义与改造前完全一致。</p>
 */
public final class BatchInsert {

    /**
     * 每批攒多少条 flush 一次。
     *
     * <p>与 P1-4 的批写口径（{@code NlpBatchServiceImpl.WRITE_BATCH_SIZE}）取同一个值：
     * 500 条一次 flush 在「往返数」与「单批内存 / 语句长度」之间是既有实践验证过的折中。</p>
     */
    public static final int BATCH_SIZE = 500;

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
        // 3. 批写：会话与 Mapper 同源取自本会话；每 BATCH_SIZE 条 flush 一次，最后补一次尾批。
        //    事务内 commit() 是空操作，事务外才是真提交 —— 见类注释。
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            M batchMapper = session.getMapper(mapperType);
            int i = 1;
            for (T row : rows) {
                batchMapper.insert(row);
                if (i % BATCH_SIZE == 0) {
                    session.flushStatements();
                }
                i++;
            }
            // 尾批：条数不是 BATCH_SIZE 整数倍时，最后不足一批的必须显式刷出
            session.flushStatements();
            session.commit();
        }
    }
}
