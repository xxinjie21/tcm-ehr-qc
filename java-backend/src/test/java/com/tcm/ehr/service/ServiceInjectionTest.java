package com.tcm.ehr.service;

import com.tcm.ehr.service.impl.ReviewWriteServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全仓 {@code @Service} 只依赖 {@code I*Service} 接口，禁止直接注入 {@code *ServiceImpl}。
 *
 * <p>与同目录的 {@code ControllerInjectionTest} 同一条约定，差别只在扫描范围：
 * 那条只管 {@code com.tcm.ehr.controller}，本条管全仓 {@code com.tcm.ehr} 下所有
 * {@code @Service}。补这条的直接原因：{@code QcBatchServiceImpl} 曾直接注入
 * {@code QcServiceImpl}（复用的 {@code processOne} 当时只声明在实现类上），
 * Controller 守卫照不到 service 内部的注入，只能靠本条钉住。</p>
 *
 * <p>范围说明：凡字段类型位于 {@code com.tcm.ehr.service} 包（含 {@code service.impl} 子包）者，
 * 都必须是接口。{@code service} 包下已无「无接口的具体工具类」——批次 26 已为
 * {@code DictProposalService} / {@code DictArchiveService} / {@code DictionaryTermStore} /
 * {@code ReviewWriteService} 补齐 {@code I*Service} 接口。</p>
 */
class ServiceInjectionTest {

    private static List<Class<?>> services() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition bd : provider.findCandidateComponents("com.tcm.ehr")) {
            types.add(Class.forName(bd.getBeanClassName()));
        }
        return types;
    }

    @Test
    void servicesInjectInterfacesOnly() throws Exception {
        List<Class<?>> services = services();
        assertTrue(services.size() >= 10,
                "扫到的 @Service 太少（" + services.size() + "）：扫描路径或过滤器写错了，这条测试会退化成空转");
        assertTrue(services.contains(ReviewWriteServiceImpl.class),
                "扫描结果里应当有 ReviewWriteServiceImpl，否则说明扫到的不是全仓 service");

        for (Class<?> service : services) {
            for (Field field : service.getDeclaredFields()) {
                Class<?> type = field.getType();
                boolean isServiceDependency = type.getPackage() != null
                        && type.getPackage().getName().startsWith("com.tcm.ehr.service");
                assertFalse(isServiceDependency && !type.isInterface(),
                        service.getSimpleName() + "." + field.getName() + " 注入了服务实现类 "
                                + type.getSimpleName()
                                + "：必须依赖 I*Service 接口，否则换实现/加测试替身都要回改调用方");
            }
        }
    }
}
