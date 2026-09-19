package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 诊疗逻辑一致性检查结果（批B·2.3，对应 openapi LogicCheckVO）。
 */
@Data
public class LogicCheckVO {

    private boolean consistent;
    private List<String> conflicts = new ArrayList<>();
}
