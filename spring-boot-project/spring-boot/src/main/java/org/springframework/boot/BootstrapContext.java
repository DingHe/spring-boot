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

import java.util.function.Supplier;

import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/**
 * A simple bootstrap context that is available during startup and {@link Environment}
 * post-processing up to the point that the {@link ApplicationContext} is prepared.
 * <p>
 * Provides lazy access to singletons that may be expensive to create, or need to be
 * shared before the {@link ApplicationContext} is available.
 * <p>
 * Instances are registered by type. The contact may return {@code null} values when a
 * type has been registered but no value is actually supplied.
 *
 * @author Phillip Webb
 * @since 2.4.0
 * @see BootstrapRegistry
 */
// 在应用启动的极早期阶段发挥作用，填补了“应用启动开始”到“正式 Spring 容器（ApplicationContext）就绪”之间的空白
// 早期资源共享：在 ApplicationContext 尚未创建之前，某些组件（如配置中心客户端、解密工具）可能就已经需要被创建并共享。BootstrapContext 提供了一个临时存储这些对象的“微型容器”。
// 延迟初始化（Lazy Access）：它支持通过 Supplier 注册单例。这意味着只有当真正调用 get 方法时，那些创建成本较高的对象才会被实例化，从而优化启动速度。
// 环境后处理支持：它主要在 Environment 后处理和 ApplicationContext 准备阶段可用。一旦正式容器准备就绪，它持有的单例通常会转交给正式容器，然后自身被销毁。
// 类型驱动：实例是根据其 Class 类型进行注册和获取的，确保了类型安全。
public interface BootstrapContext {

	/**
	 * Return an instance from the context if the type has been registered. The instance
	 * will be created if it hasn't been accessed previously.
	 * @param <T> the instance type
	 * @param type the instance type
	 * @return the instance managed by the context, which may be {@code null}
	 * @throws IllegalStateException if the type has not been registered
	 */
	// 根据类型从上下文中获取实例
	// 如果该类型已经注册过，且之前未访问过，则此时会触发创建逻辑。
	// 如果之前已经创建过，则返回缓存的单例。
	<T> T get(Class<T> type) throws IllegalStateException;

	/**
	 * Return an instance from the context if the type has been registered. The instance
	 * will be created if it hasn't been accessed previously.
	 * @param <T> the instance type
	 * @param type the instance type
	 * @param other the instance to use if the type has not been registered
	 * @return the instance, which may be {@code null}
	 */
	// 获取实例，如果未注册则返回指定的默认值。
	// 逻辑与 get 类似，但它提供了一个安全垫。
	<T> T getOrElse(Class<T> type, T other);

	/**
	 * Return an instance from the context if the type has been registered. The instance
	 * will be created if it hasn't been accessed previously.
	 * @param <T> the instance type
	 * @param type the instance type
	 * @param other a supplier for the instance to use if the type has not been registered
	 * @return the instance, which may be {@code null}
	 */
	// 作用：获取实例，如果未注册则通过 Supplier 函数获取默认值。
	// 只有当确定 type 没有被注册时，才会调用 other.get() 来生成默认值。
	// 这种方式比 getOrElse 更高效，因为它避免了在类型已存在时仍然去创建不必要的默认对象
	<T> T getOrElseSupply(Class<T> type, Supplier<T> other);

	/**
	 * Return an instance from the context if the type has been registered. The instance
	 * will be created if it hasn't been accessed previously.
	 * @param <T> the instance type
	 * @param <X> the exception to throw if the type is not registered
	 * @param type the instance type
	 * @param exceptionSupplier the supplier which will return the exception to be thrown
	 * @return the instance managed by the context, which may be {@code null}
	 * @throws X if the type has not been registered
	 */
	// 获取实例，如果未注册则抛出自定义异常。
	// 允许用户自定义在找不到注册类型时的错误处理逻辑。
	<T, X extends Throwable> T getOrElseThrow(Class<T> type, Supplier<? extends X> exceptionSupplier) throws X;

	/**
	 * Return if a registration exists for the given type.
	 * @param <T> the instance type
	 * @param type the instance type
	 * @return {@code true} if the type has already been registered
	 */
	// 作用：检查给定类型是否已在上下文中注册。
	<T> boolean isRegistered(Class<T> type);

}
