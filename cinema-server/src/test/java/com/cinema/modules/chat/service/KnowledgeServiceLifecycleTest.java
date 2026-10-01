package com.cinema.modules.chat.service;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E 2026-10-01 回归锁 —— FAQ 知识库「生产环境永远为空」。
 *
 * <p><b>缺陷</b>:{@code KnowledgeService.init()} 少了 {@code @PostConstruct} 注解。
 * 该方法唯一的生产调用点就是 Spring 容器回调,注解一丢,启动时就不会加载,
 * {@code knowledgeList} 恒为空的 {@code ArrayList},{@code searchFaq} 永远返回空 List。
 * 实测症状:助手回答「知识库里暂时没有检索到对应的说明条文(返回为空)」,
 * 而库里 {@code qa_knowledge} 明明有 18 条种子数据。
 *
 * <p><b>为什么原有 7 个单测没抓到</b>:{@code KnowledgeServiceTest} 每个 case 都在
 * {@code stubKnowledge()} 里手工调 {@code service.init()}(其注释写明
 * 「避免 @PostConstruct 依赖 Spring 上下文」),于是被测对象永远是「已初始化」状态,
 * 注解在与不在都一样绿。这类 wiring 缺陷只能靠反射或 Spring 上下文测试来锁。
 *
 * <p>与 {@code ChatToolsStructureTest} 同思路:用反射锁住一条编译期/运行期都看不见的约定。
 */
class KnowledgeServiceLifecycleTest {

    @Test
    @DisplayName("init() 必须带 @PostConstruct —— 否则生产环境 FAQ 永远不加载(E2E 2026-10-01)")
    void init_isAnnotatedWithPostConstruct() throws NoSuchMethodException {
        Method init = KnowledgeService.class.getMethod("init");
        assertThat(init.getAnnotation(PostConstruct.class))
                .as("KnowledgeService.init() 缺少 @PostConstruct,Spring 启动时不会加载 qa_knowledge")
                .isNotNull();
    }

    @Test
    @DisplayName("init() 必须是 public —— Spring 容器回调要求可访问")
    void init_isPublic() throws NoSuchMethodException {
        Method init = KnowledgeService.class.getMethod("init");
        assertThat(java.lang.reflect.Modifier.isPublic(init.getModifiers()))
                .as("KnowledgeService.init() 必须是 public")
                .isTrue();
    }
}
