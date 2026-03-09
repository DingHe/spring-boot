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

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.util.Assert;

/**
 * Default {@link ConfigurableBootstrapContext} implementation.
 *
 * @author Phillip Webb
 * @since 2.4.0
 */
// DefaultBootstrapContext 是 Spring Boot 引导阶段核心接口 ConfigurableBootstrapContext 的默认实现。它本质上是一个轻量级的、临时性的单例容器。
// 引导期容器实现：它实现了 BootstrapRegistry（写）和 BootstrapContext（读）两个接口，为 Spring Boot 启动的最早期提供对象存储和检索服务。
// 管理启动资源：在正式的 Spring 容器 ApplicationContext 刷新之前，它负责创建并管理一些必要的重型组件（如配置加解密工具、定制化的环境处理器）。
// 生命周期转换：它负责维护这些早期对象的生命周期。当 run 方法进行到 prepareContext 阶段时，它会触发关闭事件，将其管理的资源移交给正式的 Spring 容器。
public class DefaultBootstrapContext implements ConfigurableBootstrapContext {
	// 存储“实例供应者”。它记录了哪个类应该由哪个工厂逻辑来创建。
	private final Map<Class<?>, InstanceSupplier<?>> instanceSuppliers = new HashMap<>();
	// 作用：单例池。存储已经创建出来的单例对象。只有当 Scope 为 SINGLETON 时，对象才会被存入此 Map 缓存。
	private final Map<Class<?>, Object> instances = new HashMap<>();
	// 作用：事件广播器。负责管理和触发 BootstrapContextClosedEvent。
	private final ApplicationEventMulticaster events = new SimpleApplicationEventMulticaster();

	@Override
	public <T> void register(Class<T> type, InstanceSupplier<T> instanceSupplier) {
		register(type, instanceSupplier, true);
	}

	@Override
	public <T> void registerIfAbsent(Class<T> type, InstanceSupplier<T> instanceSupplier) {
		register(type, instanceSupplier, false);
	}
	// 内部统一注册逻辑。
	// 使用 synchronized 确保线程安全。它会检查该类是否已经产生了实例，如果实例已经创建（在 instances 中存在），则禁止重新注册供应者，否则会抛出异常。
	private <T> void register(Class<T> type, InstanceSupplier<T> instanceSupplier, boolean replaceExisting) {
		Assert.notNull(type, "'type' must not be null");
		Assert.notNull(instanceSupplier, "'instanceSupplier' must not be null");
		synchronized (this.instanceSuppliers) {
			boolean alreadyRegistered = this.instanceSuppliers.containsKey(type);
			if (replaceExisting || !alreadyRegistered) {
				Assert.state(!this.instances.containsKey(type), () -> type.getName() + " has already been created");
				this.instanceSuppliers.put(type, instanceSupplier);
			}
		}
	}

	@Override
	public <T> boolean isRegistered(Class<T> type) {
		synchronized (this.instanceSuppliers) {
			return this.instanceSuppliers.containsKey(type);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> InstanceSupplier<T> getRegisteredInstanceSupplier(Class<T> type) {
		synchronized (this.instanceSuppliers) {
			return (InstanceSupplier<T>) this.instanceSuppliers.get(type);
		}
	}

	@Override
	public void addCloseListener(ApplicationListener<BootstrapContextClosedEvent> listener) {
		this.events.addApplicationListener(listener);
	}

	@Override
	public <T> T get(Class<T> type) throws IllegalStateException {
		return getOrElseThrow(type, () -> new IllegalStateException(type.getName() + " has not been registered"));
	}

	@Override
	public <T> T getOrElse(Class<T> type, T other) {
		return getOrElseSupply(type, () -> other);
	}

	@Override
	public <T> T getOrElseSupply(Class<T> type, Supplier<T> other) {
		synchronized (this.instanceSuppliers) {
			InstanceSupplier<?> instanceSupplier = this.instanceSuppliers.get(type);
			return (instanceSupplier != null) ? getInstance(type, instanceSupplier) : other.get();
		}
	}

	@Override
	public <T, X extends Throwable> T getOrElseThrow(Class<T> type, Supplier<? extends X> exceptionSupplier) throws X {
		synchronized (this.instanceSuppliers) {
			InstanceSupplier<?> instanceSupplier = this.instanceSuppliers.get(type);
			if (instanceSupplier == null) {
				throw exceptionSupplier.get();
			}
			return getInstance(type, instanceSupplier);
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T getInstance(Class<T> type, InstanceSupplier<?> instanceSupplier) {
		T instance = (T) this.instances.get(type);
		if (instance == null) {
			instance = (T) instanceSupplier.get(this);
			if (instanceSupplier.getScope() == Scope.SINGLETON) {
				this.instances.put(type, instance);
			}
		}
		return instance;
	}

	/**
	 * Method to be called when {@link BootstrapContext} is closed and the
	 * {@link ApplicationContext} is prepared.
	 * @param applicationContext the prepared context
	 */
	public void close(ConfigurableApplicationContext applicationContext) {
		this.events.multicastEvent(new BootstrapContextClosedEvent(this, applicationContext));
	}

}
