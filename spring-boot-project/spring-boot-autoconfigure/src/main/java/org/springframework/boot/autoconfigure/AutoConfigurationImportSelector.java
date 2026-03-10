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

package org.springframework.boot.autoconfigure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link DeferredImportSelector} to handle {@link EnableAutoConfiguration
 * auto-configuration}. This class can also be subclassed if a custom variant of
 * {@link EnableAutoConfiguration @EnableAutoConfiguration} is needed.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Stephane Nicoll
 * @author Madhura Bhave
 * @author Moritz Halbritter
 * @author Scott Frederick
 * @since 1.3.0
 * @see EnableAutoConfiguration
 */
// Gemini said
//AutoConfigurationImportSelector 是 Spring Boot 自动配置机制的核心组件。
// 它实现了 DeferredImportSelector 接口，主要负责根据当前类路径下的依赖和配置，决定哪些 自动配置类（Auto-configuration classes） 应该被加载到 Spring 容器中。
// 该类的主要任务是实现“约定大于配置”的目标。它的执行流程通常如下：
// 扫描候选类：从 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 文件中读取所有预定义的自动配置类。
// 排除处理：根据用户在 @EnableAutoConfiguration 注解中定义的 exclude 属性或配置文件中的 spring.autoconfigure.exclude 排除指定的类。
// 条件过滤：利用 AutoConfigurationImportFilter（如检测某个 Class 是否在类路径下）过滤掉不符合条件的配置类。
// 排序导入：将最终确定的配置类按照优先级顺序（如 @AutoConfigureAfter）交给 Spring 容器进行 Bean 的初始化。
public class AutoConfigurationImportSelector implements DeferredImportSelector, BeanClassLoaderAware,
		ResourceLoaderAware, BeanFactoryAware, EnvironmentAware, Ordered {
	// 规定该选择器的执行优先级，默认比最低优先级高 1。
	static final int ORDER = Ordered.LOWEST_PRECEDENCE - 1;
	// 静态常量，表示一个空的导入条目。
	private static final AutoConfigurationEntry EMPTY_ENTRY = new AutoConfigurationEntry();
	// 返回空的导入数组。
	private static final String[] NO_IMPORTS = {};

	private static final Log logger = LogFactory.getLog(AutoConfigurationImportSelector.class);
	// 对应配置文件中的排除属性键名：spring.autoconfigure.exclude。
	private static final String PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE = "spring.autoconfigure.exclude";
	// 触发自动配置的注解类型，默认为 @AutoConfiguration。
	// 作用：它决定了 Spring Boot 去类路径下的哪个 .imports 文件中加载配置类。
	private final Class<?> autoConfigurationAnnotation;
	// Spring Bean 工厂，用于获取或操作容器中的 Bean。
	private ConfigurableListableBeanFactory beanFactory;
	// Spring 环境对象，用于读取配置属性。
	private Environment environment;
	// 类加载器，用于加载类路径下的资源文件。
	private ClassLoader beanClassLoader;
	// 资源加载器。
	private ResourceLoader resourceLoader;
	// 内部过滤器实例，用于过滤不满足条件的自动配置类。
	private volatile ConfigurationClassFilter configurationClassFilter;
	// 处理自动配置类的替换逻辑（通常用于版本迁移或兼容）
	private volatile AutoConfigurationReplacements autoConfigurationReplacements;
	// 作用：这是 Spring 框架在通过 AnnotationConfigApplicationContext 或 @EnableAutoConfiguration 自动实例化该类时默认调用的构造函数。
	// 意义：绝大多数标准的 Spring Boot 项目都使用这个构造函数，走默认的自动配置流程。
	public AutoConfigurationImportSelector() {
		this(null);
	}
	// 参数说明：autoConfigurationAnnotation 是一个注解的 Class 对象。
	// 默认情况下，寻找：META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
	AutoConfigurationImportSelector(Class<?> autoConfigurationAnnotation) {
		this.autoConfigurationAnnotation = (autoConfigurationAnnotation != null) ? autoConfigurationAnnotation
				: AutoConfiguration.class;
	}
	// 入口大闸
	// ImportSelector 接口的核心实现，Spring 容器在解析配置类时会调用它，以获取需要额外导入到容器中的类名数组。
	@Override
	public String[] selectImports(AnnotationMetadata annotationMetadata) {
		// 启用状态检查
		if (!isEnabled(annotationMetadata)) {
			return NO_IMPORTS;
		}
		// 获取自动配置条目
		// 最耗时也最重要的步骤。它会去扫描所有的 META-INF/spring/*.imports 文件，并根据当前项目的依赖情况（Classpath）进行筛选。
		// 输入：annotationMetadata（当前启动类或配置类的注解信息）。
		// Spring 容器拿到这个数组后，会依次将这些类作为 @Configuration 类进行加载。
		AutoConfigurationEntry autoConfigurationEntry = getAutoConfigurationEntry(annotationMetadata);
		return StringUtils.toStringArray(autoConfigurationEntry.getConfigurations());
	}

	@Override
	public Predicate<String> getExclusionFilter() {
		return this::shouldExclude;
	}

	private boolean shouldExclude(String configurationClassName) {
		return getConfigurationClassFilter().filter(Collections.singletonList(configurationClassName)).isEmpty();
	}

	/**
	 * Return the {@link AutoConfigurationEntry} based on the {@link AnnotationMetadata}
	 * of the importing {@link Configuration @Configuration} class.
	 * @param annotationMetadata the annotation metadata of the configuration class
	 * @return the auto-configurations that should be imported
	 */
	// 主要职责是执行自动配置的过滤算法。它从“所有可能”开始，经过“去重”、“排除”、“条件筛选”三个漏斗，最终产出一个包含“录取名单”和“淘汰名单”的 AutoConfigurationEntry 对象。
	protected AutoConfigurationEntry getAutoConfigurationEntry(AnnotationMetadata annotationMetadata) {
		if (!isEnabled(annotationMetadata)) {
			return EMPTY_ENTRY;
		}
		// 读取 @EnableAutoConfiguration 注解上的属性（如 exclude 和 excludeName），这些是用户手动指定的“黑名单”。
		AnnotationAttributes attributes = getAttributes(annotationMetadata);
		// 扫描所有 META-INF/spring/*.imports 文件，拿到当前生态下所有可用的自动配置类列表。
		List<String> configurations = getCandidateConfigurations(annotationMetadata, attributes);
		// 去重
		// 防止因为依赖冲突或重复引用导致同一个配置类被加载多次。
		configurations = removeDuplicates(configurations);
		// 将注解中的 exclude、excludeName 以及配置文件中的 spring.autoconfigure.exclude 合并成一个完整的排除集合。
		Set<String> exclusions = getExclusions(annotationMetadata, attributes);
		// 校验排除类合法性
		// 严谨性检查：如果你尝试排除一个根本不在候选名单里的类，Spring Boot 会在这里抛出异常报错，防止你写错类名。
		checkExcludedClasses(configurations, exclusions);
		// 执行排除逻辑
		// 从候选名单中物理移除用户指定的黑名单类。
		configurations.removeAll(exclusions);
		// 执行条件过滤（核心优化）
		// 根据 spring-autoconfigure-metadata.properties 检查类路径。例如，如果没有 Redis 依赖，直接在这里把 RedisAutoConfiguration 剔除，而不加载它的 Class 字节码。
		configurations = getConfigurationClassFilter().filter(configurations);
		// 触发导入事件
		fireAutoConfigurationImportEvents(configurations, exclusions);
		// 将最终结果打包返回。
		return new AutoConfigurationEntry(configurations, exclusions);
	}

	@Override
	public Class<? extends Group> getImportGroup() {
		return AutoConfigurationGroup.class;
	}
	// 自动配置机制的**“总开关”**检查
	// 检查当前应用是否允许执行自动配置。它提供了一种通过外部配置（如 application.properties）来瞬间关闭整个自动配置特性的机制。这在排查启动问题、进行集成测试或某些特定生产环境下非常有用。
	protected boolean isEnabled(AnnotationMetadata metadata) {
		if (getClass() == AutoConfigurationImportSelector.class) {
			return getEnvironment().getProperty(EnableAutoConfiguration.ENABLED_OVERRIDE_PROPERTY, Boolean.class, true);
		}
		return true;
	}

	/**
	 * Return the appropriate {@link AnnotationAttributes} from the
	 * {@link AnnotationMetadata}. By default this method will return attributes for
	 * {@link #getAnnotationClass()}.
	 * @param metadata the annotation metadata
	 * @return annotation attributes
	 */
	// 作用是解析并提取启动类（或其他配置类）上 @EnableAutoConfiguration 注解的属性值。
	// 自动化配置决策过程中的“信息采集站”，负责抓取用户在代码中手动设置的配置信息。
	// 在 Spring Boot 的启动类上，我们通常会看到 @SpringBootApplication，它包含了 @EnableAutoConfiguration。这个注解允许用户设置 exclude 或 excludeName 来手动关闭某些自动配置。
	// getAttributes 方法的工作就是把这些注解里的“参数”读取出来，转化为 Spring 能够处理的 AnnotationAttributes 对象。
	protected AnnotationAttributes getAttributes(AnnotationMetadata metadata) {
		// 在默认实现中，它返回的是 EnableAutoConfiguration.class。
		String name = getAnnotationClass().getName();
		// 从类的元数据中获取指定注解的所有属性键值对。参数 true 表示如果属性值是类（Class），则将其转换为字符串（String）类名
		// 默认获取EnableAutoConfiguration注解的属性
		AnnotationAttributes attributes = AnnotationAttributes.fromMap(metadata.getAnnotationAttributes(name, true));
		Assert.state(attributes != null, () -> "No auto-configuration attributes found. Is " + metadata.getClassName()
				+ " annotated with " + ClassUtils.getShortName(name) + "?");
		return attributes;
	}

	/**
	 * Return the source annotation class used by the selector.
	 * @return the annotation class
	 */
	// 默认返回EnableAutoConfiguration
	protected Class<?> getAnnotationClass() {
		return EnableAutoConfiguration.class;
	}

	/**
	 * Return the auto-configuration class names that should be considered. By default,
	 * this method will load candidates using {@link ImportCandidates}.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return a list of candidate configurations
	 */
	// 任务是去项目的类路径下搜索所有潜在的、可以被自动加载的配置类。
	// 负责加载原始候选名单。它并不会判断这些类是否真的需要运行（那是过滤器的活儿），它只是根据约定好的路径，把所有声明为“自动配置”的类名全部读取出来，作为后续筛选的基础。
	protected List<String> getCandidateConfigurations(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		ImportCandidates importCandidates = ImportCandidates.load(this.autoConfigurationAnnotation,
				getBeanClassLoader());
		List<String> configurations = importCandidates.getCandidates();
		Assert.state(!CollectionUtils.isEmpty(configurations),
				"No auto configuration classes found in " + "META-INF/spring/"
						+ this.autoConfigurationAnnotation.getName() + ".imports. If you "
						+ "are using a custom packaging, make sure that file is correct.");
		return configurations;
	}

	private void checkExcludedClasses(List<String> configurations, Set<String> exclusions) {
		List<String> invalidExcludes = new ArrayList<>(exclusions.size());
		ClassLoader classLoader = (this.beanClassLoader != null) ? this.beanClassLoader : getClass().getClassLoader();
		for (String exclusion : exclusions) {
			if (ClassUtils.isPresent(exclusion, classLoader) && !configurations.contains(exclusion)) {
				invalidExcludes.add(exclusion);
			}
		}
		if (!invalidExcludes.isEmpty()) {
			handleInvalidExcludes(invalidExcludes);
		}
	}

	/**
	 * Handle any invalid excludes that have been specified.
	 * @param invalidExcludes the list of invalid excludes (will always have at least one
	 * element)
	 */
	protected void handleInvalidExcludes(List<String> invalidExcludes) {
		StringBuilder message = new StringBuilder();
		for (String exclude : invalidExcludes) {
			message.append("\t- ").append(exclude).append(String.format("%n"));
		}
		throw new IllegalStateException(String.format(
				"The following classes could not be excluded because they are not auto-configuration classes:%n%s",
				message));
	}

	/**
	 * Return any exclusions that limit the candidate configurations.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return exclusions or an empty set
	 */
	// 自动配置流程中的**“黑名单汇总站”。它的唯一任务是：收集用户通过各种途径指定的、所有不希望加载**的自动配置类。
	// 这个方法体现了 Spring Boot 的配置优先级和灵活性，允许用户通过代码或外部配置文件来精准“打架”掉某些不需要的配置。
	protected Set<String> getExclusions(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		Set<String> excluded = new LinkedHashSet<>();
		// 收集注解中的 Class 排除项
		// 来源：@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class})。
		excluded.addAll(asList(attributes, "exclude"));
		// 来源：@EnableAutoConfiguration(excludeName = "org.example.MyConfig")。
		excluded.addAll(asList(attributes, "excludeName"));
		// 来源：application.properties 或 yaml 文件中的 spring.autoconfigure.exclude 属性。
		excluded.addAll(getExcludeAutoConfigurationsProperty());
		// 随着 Spring Boot 版本升级，某些自动配置类可能会改名或迁移包路径。
		// 价值：如果用户在配置中写了旧版本的类名，该逻辑会将其自动替换为新版本的类名，确保排除指令在版本升级后依然生效，不会因为类名变更而失效。
		return getAutoConfigurationReplacements().replaceAll(excluded);
	}

	/**
	 * Returns the auto-configurations excluded by the
	 * {@code spring.autoconfigure.exclude} property.
	 * @return excluded auto-configurations
	 * @since 2.3.2
	 */
	protected List<String> getExcludeAutoConfigurationsProperty() {
		Environment environment = getEnvironment();
		if (environment == null) {
			return Collections.emptyList();
		}
		if (environment instanceof ConfigurableEnvironment) {
			Binder binder = Binder.get(environment);
			return binder.bind(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class)
				.map(Arrays::asList)
				.orElse(Collections.emptyList());
		}
		String[] excludes = environment.getProperty(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class);
		return (excludes != null) ? Arrays.asList(excludes) : Collections.emptyList();
	}

	protected List<AutoConfigurationImportFilter> getAutoConfigurationImportFilters() {
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportFilter.class, this.beanClassLoader);
	}
	// 在执行真正的自动配置筛选之前，必须先准备好“筛选器”。
	// 这个方法负责从 spring.factories 加载所有的过滤插件（如检查类是否在类路径下的 OnClassCondition），
	// 并确保这些插件能够正常访问 Spring 的环境（Environment）和工厂（BeanFactory）。
	private ConfigurationClassFilter getConfigurationClassFilter() {
		ConfigurationClassFilter configurationClassFilter = this.configurationClassFilter;
		if (configurationClassFilter == null) {
			// 调用 getAutoConfigurationImportFilters()。它会去扫描所有 Jar 包下的 META-INF/spring.factories，寻找 AutoConfigurationImportFilter 接口的实现类。
			List<AutoConfigurationImportFilter> filters = getAutoConfigurationImportFilters();
			for (AutoConfigurationImportFilter filter : filters) {
				// 注入 Spring 基础设施（Aware 回调）
				invokeAwareMethods(filter);
			}
			// 将类加载器和配置好的过滤器列表传给内部类 ConfigurationClassFilter。
			configurationClassFilter = new ConfigurationClassFilter(this.beanClassLoader, filters);
			this.configurationClassFilter = configurationClassFilter;
		}
		return configurationClassFilter;
	}

	private AutoConfigurationReplacements getAutoConfigurationReplacements() {
		AutoConfigurationReplacements autoConfigurationReplacements = this.autoConfigurationReplacements;
		if (autoConfigurationReplacements == null) {
			autoConfigurationReplacements = AutoConfigurationReplacements.load(this.autoConfigurationAnnotation,
					this.beanClassLoader);
			this.autoConfigurationReplacements = autoConfigurationReplacements;
		}
		return autoConfigurationReplacements;
	}

	protected final <T> List<T> removeDuplicates(List<T> list) {
		return new ArrayList<>(new LinkedHashSet<>(list));
	}

	protected final List<String> asList(AnnotationAttributes attributes, String name) {
		String[] value = attributes.getStringArray(name);
		return Arrays.asList(value);
	}
	// 自动配置过程中的**“通告系统”**。它的任务是在自动配置的筛选工作全部完成后，将结果（哪些被导入、哪些被排除）告知所有感兴趣的监听器。
	// 将返回最终结果之前，它会触发此方法。通过发布事件，该方法允许外部组件观察自动配置的决策结果。最主要的受益者是 Spring Boot 的诊断工具，它们利用这些数据向开发者展示为什么某个配置生效了，而另一个没有。
	private void fireAutoConfigurationImportEvents(List<String> configurations, Set<String> exclusions) {
		List<AutoConfigurationImportListener> listeners = getAutoConfigurationImportListeners();
		if (!listeners.isEmpty()) {
			AutoConfigurationImportEvent event = new AutoConfigurationImportEvent(this, configurations, exclusions);
			for (AutoConfigurationImportListener listener : listeners) {
				invokeAwareMethods(listener);
				listener.onAutoConfigurationImportEvent(event);
			}
		}
	}

	protected List<AutoConfigurationImportListener> getAutoConfigurationImportListeners() {
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportListener.class, this.beanClassLoader);
	}
	// 注入 Spring 基础设施（Aware 回调）
	private void invokeAwareMethods(Object instance) {
		if (instance instanceof Aware) {
			if (instance instanceof BeanClassLoaderAware beanClassLoaderAwareInstance) {
				beanClassLoaderAwareInstance.setBeanClassLoader(this.beanClassLoader);
			}
			if (instance instanceof BeanFactoryAware beanFactoryAwareInstance) {
				beanFactoryAwareInstance.setBeanFactory(this.beanFactory);
			}
			if (instance instanceof EnvironmentAware environmentAwareInstance) {
				environmentAwareInstance.setEnvironment(this.environment);
			}
			if (instance instanceof ResourceLoaderAware resourceLoaderAwareInstance) {
				resourceLoaderAwareInstance.setResourceLoader(this.resourceLoader);
			}
		}
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		Assert.isInstanceOf(ConfigurableListableBeanFactory.class, beanFactory);
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}

	protected final ConfigurableListableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
	}

	protected ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	protected final Environment getEnvironment() {
		return this.environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	protected final ResourceLoader getResourceLoader() {
		return this.resourceLoader;
	}

	@Override
	public int getOrder() {
		return ORDER;
	}
	// 在 Spring Boot 启动时，可能有几百个自动配置类候选项。如果每个类都去加载字节码并解析注解，启动会非常慢。这个类通过“预过滤”机制，在不加载类的情况下剔除不符合条件的配置。
	private static class ConfigurationClassFilter {
		// 存储自动配置的元数据。
		// 通过 AutoConfigurationMetadataLoader 加载，通常读取的是 META-INF/spring-autoconfigure-metadata.properties 文件。
		// 价值：这个文件包含了配置类上的条件信息（如 @ConditionalOnClass）。有了它，Spring 可以在不加载类字节码的前提下判断某个类是否应该被过滤，从而极大提高启动速度。
		private final AutoConfigurationMetadata autoConfigurationMetadata;
		// 作用：持有一组过滤器插件。
		// 细节：这些过滤器通常是从 spring.factories 中加载的（如 OnClassCondition），专门用于执行具体的匹配逻辑。
		private final List<AutoConfigurationImportFilter> filters;

		ConfigurationClassFilter(ClassLoader classLoader, List<AutoConfigurationImportFilter> filters) {
			this.autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(classLoader);
			this.filters = filters;
		}
		// 核心过滤逻辑
		List<String> filter(List<String> configurations) {
			long startTime = System.nanoTime();
			// 将 configurations 列表转为 String[] candidates 数组，方便按索引操作。
			String[] candidates = StringUtils.toStringArray(configurations);
			boolean skipped = false;
			for (AutoConfigurationImportFilter filter : this.filters) {
				// 遍历所有的 filters。
				// 返回一个 boolean[] match 数组，长度与候选类数组一致。
				boolean[] match = filter.match(candidates, this.autoConfigurationMetadata);
				for (int i = 0; i < match.length; i++) {
					if (!match[i]) {
						candidates[i] = null;
						skipped = true;
					}
				}
			}
			// 优化返回：如果没有类被过滤掉（!skipped），直接返回原始列表，避免创建新对象的开销。
			if (!skipped) {
				return configurations;
			}
			// 收集结果：如果发生了过滤，遍历数组，将非 null 的元素（即匹配成功的类）加入新的 result 列表。
			List<String> result = new ArrayList<>(candidates.length);
			for (String candidate : candidates) {
				if (candidate != null) {
					result.add(candidate);
				}
			}
			if (logger.isTraceEnabled()) {
				int numberFiltered = configurations.size() - result.size();
				logger.trace("Filtered " + numberFiltered + " auto configuration class in "
						+ TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime) + " ms");
			}
			return result;
		}

	}

	private static final class AutoConfigurationGroup
			implements DeferredImportSelector.Group, BeanClassLoaderAware, BeanFactoryAware, ResourceLoaderAware {

		private final Map<String, AnnotationMetadata> entries = new LinkedHashMap<>();

		private final List<AutoConfigurationEntry> autoConfigurationEntries = new ArrayList<>();

		private ClassLoader beanClassLoader;

		private BeanFactory beanFactory;

		private ResourceLoader resourceLoader;

		private AutoConfigurationMetadata autoConfigurationMetadata;

		private AutoConfigurationReplacements autoConfigurationReplacements;

		@Override
		public void setBeanClassLoader(ClassLoader classLoader) {
			this.beanClassLoader = classLoader;
		}

		@Override
		public void setBeanFactory(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public void setResourceLoader(ResourceLoader resourceLoader) {
			this.resourceLoader = resourceLoader;
		}

		@Override
		public void process(AnnotationMetadata annotationMetadata, DeferredImportSelector deferredImportSelector) {
			Assert.state(deferredImportSelector instanceof AutoConfigurationImportSelector,
					() -> String.format("Only %s implementations are supported, got %s",
							AutoConfigurationImportSelector.class.getSimpleName(),
							deferredImportSelector.getClass().getName()));
			AutoConfigurationImportSelector autoConfigurationImportSelector = (AutoConfigurationImportSelector) deferredImportSelector;
			AutoConfigurationReplacements autoConfigurationReplacements = autoConfigurationImportSelector
				.getAutoConfigurationReplacements();
			Assert.state(
					this.autoConfigurationReplacements == null
							|| this.autoConfigurationReplacements.equals(autoConfigurationReplacements),
					"Auto-configuration replacements must be the same for each call to process");
			this.autoConfigurationReplacements = autoConfigurationReplacements;
			AutoConfigurationEntry autoConfigurationEntry = autoConfigurationImportSelector
				.getAutoConfigurationEntry(annotationMetadata);
			this.autoConfigurationEntries.add(autoConfigurationEntry);
			for (String importClassName : autoConfigurationEntry.getConfigurations()) {
				this.entries.putIfAbsent(importClassName, annotationMetadata);
			}
		}

		@Override
		public Iterable<Entry> selectImports() {
			if (this.autoConfigurationEntries.isEmpty()) {
				return Collections.emptyList();
			}
			Set<String> allExclusions = this.autoConfigurationEntries.stream()
				.map(AutoConfigurationEntry::getExclusions)
				.flatMap(Collection::stream)
				.collect(Collectors.toSet());
			Set<String> processedConfigurations = this.autoConfigurationEntries.stream()
				.map(AutoConfigurationEntry::getConfigurations)
				.flatMap(Collection::stream)
				.collect(Collectors.toCollection(LinkedHashSet::new));
			processedConfigurations.removeAll(allExclusions);
			return sortAutoConfigurations(processedConfigurations, getAutoConfigurationMetadata()).stream()
				.map((importClassName) -> new Entry(this.entries.get(importClassName), importClassName))
				.toList();
		}

		private AutoConfigurationMetadata getAutoConfigurationMetadata() {
			if (this.autoConfigurationMetadata == null) {
				this.autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(this.beanClassLoader);
			}
			return this.autoConfigurationMetadata;
		}

		private List<String> sortAutoConfigurations(Set<String> configurations,
				AutoConfigurationMetadata autoConfigurationMetadata) {
			return new AutoConfigurationSorter(getMetadataReaderFactory(), autoConfigurationMetadata,
					this.autoConfigurationReplacements::replace)
				.getInPriorityOrder(configurations);
		}

		private MetadataReaderFactory getMetadataReaderFactory() {
			try {
				return this.beanFactory.getBean(SharedMetadataReaderFactoryContextInitializer.BEAN_NAME,
						MetadataReaderFactory.class);
			}
			catch (NoSuchBeanDefinitionException ex) {
				return new CachingMetadataReaderFactory(this.resourceLoader);
			}
		}

	}
	// AutoConfigurationEntry 是一个不可变的（Immutable）数据传输对象（DTO）。
	//它的存在是为了将“最终确定的自动配置类列表”和“在此过程中被排除掉的类列表”打包在一起。
	// 这样做的好处是，在后续处理（如打印日志、触发事件或在 AutoConfigurationGroup 中进行全局合并）时，我们不仅知道哪些类要加载，还能追溯哪些类被过滤掉了。
	protected static class AutoConfigurationEntry {
		// 存储经过筛选、去重、过滤后，最终决定要导入到 Spring 容器中的自动配置类的全限定类名（Fully Qualified Class Names）。
		private final List<String> configurations;
		// 存储在该步骤中被明确排除掉的类名。这些排除项可能来自 @EnableAutoConfiguration(exclude=...)，也可能来自配置文件中的 spring.autoconfigure.exclude。
		private final Set<String> exclusions;

		private AutoConfigurationEntry() {
			this.configurations = Collections.emptyList();
			this.exclusions = Collections.emptySet();
		}

		/**
		 * Create an entry with the configurations that were contributed and their
		 * exclusions.
		 * @param configurations the configurations that should be imported
		 * @param exclusions the exclusions that were applied to the original list
		 */
		AutoConfigurationEntry(Collection<String> configurations, Collection<String> exclusions) {
			this.configurations = new ArrayList<>(configurations);
			this.exclusions = new HashSet<>(exclusions);
		}

		public List<String> getConfigurations() {
			return this.configurations;
		}

		public Set<String> getExclusions() {
			return this.exclusions;
		}

	}

}
