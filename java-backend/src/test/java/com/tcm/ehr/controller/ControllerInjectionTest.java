package com.tcm.ehr.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Controller 只依赖 {@code I*Service} 接口，禁止直接注入 {@code *ServiceImpl}。
 *
 * <p>约定本身写在 docs/开发指南.md（「依赖管理 · 跨模块只经接口」），但文档管不住新人，
 * 能管住的是编译与测试。这里做机械检查：反射扫出 {@code com.tcm.ehr.controller} 下所有
 * {@code @RestController}，断言其字段类型里没有实现类。</p>
 *
 * <p>为什么值得钉住：接口层存在的唯一理由是「调用方只认识契约」。一旦 Controller 直接
 * 注入实现类，换实现、加测试替身都要回改 Controller，接口层就退化成一层空转——
 * 那还不如当初别建（这正是批次 26.c 曾提议删接口、最终决定保留的取舍）。</p>
 *
 * <p>范围说明：凡字段类型位于 {@code com.tcm.ehr.service} 包（含 {@code service.impl} 子包）者，
 * 都必须是接口。{@code service} 包下已无「无接口的具体工具类」——批次 26 已为
 * {@code DictProposalService} / {@code DictArchiveService} / {@code DictionaryTermStore} /
 * {@code ReviewWriteService} 补齐 {@code I*Service} 接口。</p>
 */
class ControllerInjectionTest {

    private static List<Class<?>> controllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition bd : provider.findCandidateComponents("com.tcm.ehr.controller")) {
            types.add(Class.forName(bd.getBeanClassName()));
        }
        return types;
    }

    @Test
    void controllersInjectInterfacesOnly() throws Exception {
        List<Class<?>> controllers = controllers();
        assertTrue(controllers.size() >= 1,
                "一个 @RestController 都没扫到：扫描路径或过滤器写错了，这条测试会退化成空转");
        assertTrue(controllers.contains(StatsController.class),
                "扫描结果里应当有 StatsController，否则说明扫到的不是本包");

        for (Class<?> controller : controllers) {
            for (Field field : controller.getDeclaredFields()) {
                Class<?> type = field.getType();
                boolean isServiceDependency = type.getPackage() != null
                        && type.getPackage().getName().startsWith("com.tcm.ehr.service");
                assertFalse(isServiceDependency && !type.isInterface(),
                        controller.getSimpleName() + "." + field.getName() + " 注入了服务实现类 "
                                + type.getSimpleName()
                                + "：Controller 必须依赖 I*Service 接口，否则换实现/加测试替身都要回改 Controller");
            }
        }
    }
}
