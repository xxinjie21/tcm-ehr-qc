package com.tcm.ehr.vo;

import lombok.Data;

import java.util.List;

@Data
public class LoginVO {

    private String token;
    private String role;
    private List<String> menus;
}
