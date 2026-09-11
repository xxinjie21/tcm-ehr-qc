package com.tcm.ehr.mapper;

import com.tcm.ehr.entity.ReviewTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReviewTaskMapper {

    int insert(ReviewTask task);

    ReviewTask findById(String id);
}
