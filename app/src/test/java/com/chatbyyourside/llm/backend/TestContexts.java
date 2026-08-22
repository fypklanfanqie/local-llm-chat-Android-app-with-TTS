package com.chatbyyourside.llm.backend;

import android.content.Context;
import android.content.ContextWrapper;

/**
 * 单元测试用「只存引用、不触达框架方法」的 Context 替身。
 *
 * 实现：{@link ContextWrapper}(null base)。ContextWrapper 是具体类，已实现 Context 全部抽象方法
 * （委托给 base），因此无需手写抽象方法覆写；实例非空，可安全通过 Kotlin 非空参数检查。
 * 任何方法调用会因 null base 抛 NPE，但本测试只把 Context 传给 BackendManager 构造存引用，
 * 不触达框架方法（inferenceSession.begin/end 的调用都被 runCatching 包裹，null 安全降级；
 * deviceCapability 惰性且本测试从不读取）。
 *
 * 为何不用 android.test.mock.MockContext：AGP 单元测试 classpath 的 android.jar 不包含
 * `android.test.mock` 包，解析不了。
 */
public final class TestContexts {
    private TestContexts() {}

    private static final Context NULL_CONTEXT = new ContextWrapper(null);

    /** 返回仅可存引用的 Context 替身（方法调用会 NPE，仅测试传引用不触达时用）。 */
    public static Context nullContext() {
        return NULL_CONTEXT;
    }
}
