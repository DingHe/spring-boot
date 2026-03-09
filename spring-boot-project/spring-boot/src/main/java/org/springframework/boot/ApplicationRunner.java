/*
 * Copyright 2012-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Interface used to indicate that a bean should <em>run</em> when it is contained within
 * a {@link SpringApplication}. Multiple {@link ApplicationRunner} beans can be defined
 * within the same application context and can be ordered using the {@link Ordered}
 * interface or {@link Order @Order} annotation.
 *
 * @author Phillip Webb
 * @since 1.3.0
 * @see CommandLineRunner
 */
// 专门用于处理那些需要在 Spring 容器启动完成后立即执行的逻辑
// 启动后的回调机制：当 SpringApplication.run() 执行到最后阶段，即 Spring 容器（ApplicationContext）已经刷新（Refresh）完毕、所有的 Bean 都已实例化、内嵌 Web 服务器也已启动后，它会自动调用所有实现此接口的 Bean。
// 高级参数解析：与 CommandLineRunner 接收原始字符串数组不同，ApplicationRunner 接收的是封装好的 ApplicationArguments 对象。这使得开发者可以非常方便地获取“选项参数”（如 --port=8080）和“非选项参数”。
// 有序执行：支持在一个应用中定义多个 ApplicationRunner。你可以通过实现 Ordered 接口或使用 @Order 注解来精确控制它们的执行顺序（数字越小，优先级越高）。
// 业务初始化：常用于在应用正式对外提供服务前进行数据预热、检查配置文件、建立远程连接或启动某些后台守护线程。
@FunctionalInterface
public interface ApplicationRunner extends Runner {

	/**
	 * Callback used to run the bean.
	 * @param args incoming application arguments
	 * @throws Exception on error
	 */
	// 作用：作为 Bean 被容器加载后的执行入口。
	// ApplicationArguments args：这是该接口的核心优势。它是 Spring 对命令行参数的封装。通过它，你可以调用 args.getOptionValues("name") 直接获取 --name=value 格式的值，而不需要手动去解析字符串数组。
	void run(ApplicationArguments args) throws Exception;

}
