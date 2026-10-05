package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.ReviewTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    /**
     * 幂等 upsert：把该病历唯一一条未作废任务刷成「待复核 + 最新评分」。
     *
     * <p><b>为什么必须是 SQL 语义</b>：批次 4 给 {@code review_tasks} 加了唯一键
     * {@code uk_record_active}，它建在生成列 {@code active_key} 上 —— 只对「未作废」的行唯一
     * （作废行不参与，故同一病历可以留多条历史）。原先的
     * 「先 selectList 再 update/insert」是 check-then-act，两个并发线程都能查到空、
     * 都去 insert，其中一个撞唯一键 → 从「静默重复」变成「整批 500」。
     * {@code INSERT ... ON DUPLICATE KEY UPDATE} 由唯一索引直接仲裁，不存在这个窗口。</p>
     *
     * <p>「先作废历史、再插入新的」这条老路（作废而不是删除，保留复核轨迹）仍然需要，
     * 所以本方法只覆盖「同一 (record_id, is_obsolete=0) 这唯一一行」的刷新，
     * 作废动作由 {@link #obsoleteActive(Long, java.time.LocalDateTime)} 单独做。</p>
     *
     * @param recordId 病历 ID
     * @param score    最新评分
     * @param issueType 问题类型
     * @param now      当前时间（写 create_time / deadline_time 的基准）
     * @return 受影响行数（1=新建或刷新成功）
     */
    @Update("""
            INSERT INTO review_tasks
              (id, record_id, org_id, score, issue_type, status, is_obsolete,
               create_time, deadline_time)
            VALUES
              (REPLACE(UUID(), #{recordId}, #{orgId}, #{score}, #{issueType}, 'pending', 0,
               #{now}, #{deadline})
            ON DUPLICATE KEY UPDATE
              score = VALUES(score),
              issue_type = VALUES(issue_type),
              status = 'pending',
              create_time = VALUES(create_time),
              deadline_time = VALUES(deadline_time),
              reviewed_by = NULL,
              completed_time = NULL
            """)
    int upsertPending(@Param("recordId") String recordId,
                      @Param("orgId") String orgId,
                      @Param("score") Integer score,
                      @Param("issueType") String issueType,
                      @Param("now") java.time.LocalDateTime now,
                      @Param("deadline") java.time.LocalDateTime deadline);

    /**
     * 把该病历所有未作废任务置为作废（评分已达标时用；作废而不是删除，历史轨迹要留）。
     *
     * completed_time 记作「作废时刻」：这个参数以前收了却没用，导致任务何时、因哪一次重算
     * 被作废不可追溯。
     *
     * @param recordId 病历 ID
     * @param now      作废时间
     * @return 受影响行数
     */
    @Update("""
            UPDATE review_tasks SET is_obsolete = 1, status = 'obsolete', completed_time = #{now}
             WHERE record_id = #{recordId} AND is_obsolete = 0
            """)
    int obsoleteActive(@Param("recordId") String recordId,
                       @Param("now") java.time.LocalDateTime now);
}
