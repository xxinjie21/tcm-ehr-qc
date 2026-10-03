package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单节点（登录响应 {@code menus} 树的元素，批次 17）。
 *
 * <p>父项没有 {@code children} 时就是叶子节点。前端 {@code MainLayout} 按
 * {@code children} 是否为空决定渲染成叶子项还是分组。</p>
 */
@Data
public class MenuNode {

    /** 菜单显示名，与前端 MainLayout 的 ALL_MENUS[].title 对应 */
    private String title;

    /**
     * 前端路由路径。
     *
     * <p>父项「术语词典」本身也有路径（{@code /dictionary}）—— 用户点父项直接进入
     * 日常的词典管理，而不是展开一个需要展开交互的树。</p>
     */
    private String path;

    /** 子菜单；空列表表示叶子节点 */
    private List<MenuNode> children = new ArrayList<>();

    public MenuNode() {
    }

    public MenuNode(String title, String path) {
        this.title = title;
        this.path = path;
    }

    public MenuNode(String title, String path, List<MenuNode> children) {
        this.title = title;
        this.path = path;
        this.children = children == null ? new ArrayList<>() : children;
    }
}
