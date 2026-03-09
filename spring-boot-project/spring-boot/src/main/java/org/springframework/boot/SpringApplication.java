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

import java.lang.StackWalker.StackFrame;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.crac.management.CRaCMXBean;

import org.springframework.aot.AotDetector;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.groovy.GroovyBeanDefinitionReader;
import org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.boot.Banner.Mode;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.web.reactive.context.AnnotationConfigReactiveWebServerApplicationContext;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.aot.AotApplicationContextInitializer;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.NativeDetector;
import org.springframework.core.OrderComparator;
import org.springframework.core.OrderComparator.OrderSourceProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.function.ThrowingConsumer;
import org.springframework.util.function.ThrowingSupplier;

/**
 * Class that can be used to bootstrap and launch a Spring application from a Java main
 * method. By default class will perform the following steps to bootstrap your
 * application:
 *
 * <ul>
 * <li>Create an appropriate {@link ApplicationContext} instance (depending on your
 * classpath)</li>
 * <li>Register a {@link CommandLinePropertySource} to expose command line arguments as
 * Spring properties</li>
 * <li>Refresh the application context, loading all singleton beans</li>
 * <li>Trigger any {@link CommandLineRunner} beans</li>
 * </ul>
 *
 * In most circumstances the static {@link #run(Class, String[])} method can be called
 * directly from your {@literal main} method to bootstrap your application:
 *
 * <pre class="code">
 * &#064;Configuration
 * &#064;EnableAutoConfiguration
 * public class MyApplication  {
 *
 *   // ... Bean definitions
 *
 *   public static void main(String[] args) {
 *     SpringApplication.run(MyApplication.class, args);
 *   }
 * }
 * </pre>
 *
 * <p>
 * For more advanced configuration a {@link SpringApplication} instance can be created and
 * customized before being run:
 *
 * <pre class="code">
 * public static void main(String[] args) {
 *   SpringApplication application = new SpringApplication(MyApplication.class);
 *   // ... customize application settings here
 *   application.run(args)
 * }
 * </pre>
 *
 * {@link SpringApplication}s can read beans from a variety of different sources. It is
 * generally recommended that a single {@code @Configuration} class is used to bootstrap
 * your application, however, you may also set {@link #getSources() sources} from:
 * <ul>
 * <li>The fully qualified class name to be loaded by
 * {@link AnnotatedBeanDefinitionReader}</li>
 * <li>The location of an XML resource to be loaded by {@link XmlBeanDefinitionReader}, or
 * a groovy script to be loaded by {@link GroovyBeanDefinitionReader}</li>
 * <li>The name of a package to be scanned by {@link ClassPathBeanDefinitionScanner}</li>
 * </ul>
 *
 * Configuration properties are also bound to the {@link SpringApplication}. This makes it
 * possible to set {@link SpringApplication} properties dynamically, like additional
 * sources ("spring.main.sources" - a CSV list) the flag to indicate a web environment
 * ("spring.main.web-application-type=none") or the flag to switch off the banner
 * ("spring.main.banner-mode=off").
 *
 * @author Phillip Webb
 * @author Dave Syer
 * @author Andy Wilkinson
 * @author Christian Dupuis
 * @author Stephane Nicoll
 * @author Jeremy Rickard
 * @author Craig Burke
 * @author Michael Simons
 * @author Madhura Bhave
 * @author Brian Clozel
 * @author Ethan Rubinson
 * @author Chris Bono
 * @author Moritz Halbritter
 * @author Tadaya Tsuyukubo
 * @author Lasse Wulff
 * @author Yanming Zhou
 * @since 1.0.0
 * @see #run(Class, String[])
 * @see #run(Class[], String[])
 * @see #SpringApplication(Class...)
 */
// SpringApplication 是 Spring Boot 的核心启动类。它负责引导和启动 Spring 应用，自动化配置 Spring 容器（ApplicationContext），并管理整个启动过程的生命周期。
// 容器引导：根据类路径（Classpath）自动推断并创建合适的 ApplicationContext 实例（如 Servlet Web、Reactive Web 或普通非 Web 容器）
// 环境配置：注册 CommandLinePropertySource，将命令行参数暴露为 Spring 属性，并加载外部配置文件。
// 初始化与监听：加载并触发 ApplicationContextInitializer 和 ApplicationListener。
// 横幅打印：在控制台打印 Spring Boot 的 Banner。
// Bean 加载：从指定的源（Primary Sources）加载 Bean 定义。
// 运行器触发：应用启动完成后，自动调用所有 CommandLineRunner 和 ApplicationRunner 接口的实现。
public class SpringApplication {

	/**
	 * Default banner location.
	 */
	// 定义了默认的 Banner（横幅）文件路径。
	// Spring Boot 启动时会在控制台打印一个“Spring”样式的字符画。该变量引用了 SpringApplicationBannerPrinter 中的默认值（通常是 banner.txt）。
	public static final String BANNER_LOCATION_PROPERTY_VALUE = SpringApplicationBannerPrinter.DEFAULT_BANNER_LOCATION;

	/**
	 * Banner location property key.
	 */
	// 定义了配置 Banner 路径的属性键（Key）。
	// 对应配置文件中的 spring.banner.location。通过这个键，用户可以在 application.properties 中自定义 Banner 文件的存放位置。
	public static final String BANNER_LOCATION_PROPERTY = SpringApplicationBannerPrinter.BANNER_LOCATION_PROPERTY;
	// 存储 Java AWT 的 "headless" 模式系统属性名。
	// 值为 "java.awt.headless"。在服务器环境（无显示器）运行时，设置为 true 可以防止 AWT 库尝试链接显示设备，避免启动报错。
	private static final String SYSTEM_PROPERTY_JAVA_AWT_HEADLESS = "java.awt.headless";

	private static final Log logger = LogFactory.getLog(SpringApplication.class);
	// Spring Boot 应用的关闭钩子
	// 确保在 JVM 关闭时（如按下 Ctrl+C），能够优雅地关闭 Spring 容器，释放资源（如数据库连接池、线程池）
	static final SpringApplicationShutdownHook shutdownHook = new SpringApplicationShutdownHook();
	// 本地线程变量，用于存储 SpringApplicationHook
	// 通常用于内部扩展或测试场景，允许在特定线程中拦截或修改 SpringApplication 的运行行为
	private static final ThreadLocal<SpringApplicationHook> applicationHook = new ThreadLocal<>();
	// 存储应用的主配置源
	// 通常包含你在 main 方法中传入的类（即标注了 @SpringBootApplication 的类）。Spring 会从这些“源”开始扫描并加载 Bean。
	// 1、最常见的单源启动（Standard Boot）
	// 这是 99% 的 Spring Boot 项目采用的方式。你的 main 类本身就是唯一的 primarySource。
	// Spring 会以这个类为起点，扫描同级包及其子包下的所有 @Component、@Service 等。
	// 2、 多源混合启动（Multiple Sources）
	// 有时为了模块解耦，你可能希望从多个互不隶属的配置类启动，而不是完全依赖组件扫描。
	// public class MultiSourceLauncher {
	//    public static void main(String[] args) {
	//        // 同时传入两个核心配置类
	//        Class<?>[] sources = { CoreConfig.class, SecondaryModule.class };
	//        SpringApplication.run(sources, args);
	//    }
	//}
	//
	//@Configuration
	//class CoreConfig { /* 核心 Bean 定义 */ }
	//
	//@Configuration
	//class SecondaryModule { /* 另一个模块的 Bean 定义 */ }
	// 此时 primarySources 集合中包含这两个类。Spring 会同时处理这两个配置类定义的 Bean，适用于合并多个独立模块的场景。
	private final Set<Class<?>> primarySources;
	// 标识包含 main 方法的类
	// Spring Boot 会在启动时通过堆栈跟踪自动推断出是哪个类启动了应用，并将其存储在此。它主要用于日志输出和 AOT（提前编译）过程中的逻辑处理。
	private Class<?> mainApplicationClass;
	// 作用：是否将命令行参数添加到 Spring 环境中。
	// 详细说明：默认值为 true。如果开启，你在运行程序时输入的参数（如 --server.port=8081）会被转换为 Spring 的 PropertySource，且优先级极高，可以覆盖配置文件中的设置。
	private boolean addCommandLineProperties = true;
	// 作用：是否自动向环境中添加类型转换服务。
	// 默认值为 true。开启后，Spring 会注册 ApplicationConversionService，它支持将配置文件中的字符串自动转换为复杂对象（如将 "10s" 转换为 java.time.Duration）
	private boolean addConversionService = true;
	// 自定义 Banner 对象。
	private Banner banner;
	// 资源加载器。
	// 用于读取配置文件、类路径资源等。如果在构造时传入了特定的 ResourceLoader，Spring 容器也将使用它。
	private ResourceLoader resourceLoader;
	// Bean 名称生成器。
	// 用于自定义 Bean 的默认命名规则。默认情况下，Spring 使用类名首字母小写作为 Bean ID，但在复杂的插件化架构中，可能需要通过此属性自定义命名逻辑以防冲突。
	private BeanNameGenerator beanNameGenerator;
	// 应用的环境配置对象。
	// 保存了所有的配置属性（Properties）和激活的配置文件（Profiles）。如果在运行前手动设置了此值，isCustomEnvironment 将变为 true。
	private ConfigurableEnvironment environment;
	// 是否开启 Headless 模式。
	// 默认值为 true。对应上文提到的 java.awt.headless 系统属性，确保在无显示器的 Linux 服务器上不会因为 AWT 库初始化失败而挂掉。
	private boolean headless = true;
	// 应用上下文初始化器列表。
	// 在 ApplicationContext 刷新（refresh）之前，这些初始化器会被依次调用，常用于硬编码方式向容器注册 Bean 或修改环境。
	private List<ApplicationContextInitializer<?>> initializers;
	// 应用监听器列表。
	// 监听 Spring Boot 启动过程中的各种事件（如：正在启动、环境准备就绪、启动完成等）。
	private List<ApplicationListener<?>> listeners;
	// 默认属性映射表。
	// 用于存放通过代码设置的默认配置。它的优先级最低，任何配置文件或命令行参数都可以覆盖这里的值。
	private Map<String, Object> defaultProperties;
	// 引导注册表初始化器。
	// 这是 Spring Boot 2.4+ 引入的特性，用于在极早期（容器还没创建前）共享一些轻量级对象，常用于加密解密组件的预加载。
	private final List<BootstrapRegistryInitializer> bootstrapRegistryInitializers;
	// 额外激活的配置文件（Profiles）。
	// 除了 application.properties 里指定的 Profile 外，通过此属性可以在代码中强行激活额外的环境，如 "dev" 或 "test"。
	private Set<String> additionalProfiles = Collections.emptySet();
	// 判断是否使用了用户自定义的 Environment。
	// 如果用户通过 setEnvironment() 手动注入了环境对象，该值为 true，Spring Boot 将跳过默认的环境创建逻辑。
	private boolean isCustomEnvironment;
	// 环境配置前缀。
	// 用于限定配置属性的作用域，通常在集成到其他框架时使用。
	private String environmentPrefix;
	// 应用上下文工厂。
	// 核心策略接口。它决定了最终创建的是 AnnotationConfigServletWebServerApplicationContext（Web 容器）还是普通的 AnnotationConfigApplicationContext
	private ApplicationContextFactory applicationContextFactory = ApplicationContextFactory.DEFAULT;
	// 启动步骤监控工具
	private ApplicationStartup applicationStartup = ApplicationStartup.DEFAULT;
	// SpringApplication 内部配置属性
	// 封装了诸如 logStartupInfo、isKeepAlive 等控制开关。它会通过 Binder 机制与环境中的 spring.main.* 配置自动绑定
	final ApplicationProperties properties = new ApplicationProperties();

	/**
	 * Create a new {@link SpringApplication} instance. The application context will load
	 * beans from the specified primary sources (see {@link SpringApplication class-level}
	 * documentation for details). The instance can be customized before calling
	 * {@link #run(String...)}.
	 * @param primarySources the primary bean sources
	 * @see #run(Class, String[])
	 * @see #SpringApplication(ResourceLoader, Class...)
	 * @see #setSources(Set)
	 */
	// 允许用户只传入主配置类（Primary Sources），内部通过调用 this(null, primarySources) 将 ResourceLoader 设为 null，进入核心构造逻辑。
	public SpringApplication(Class<?>... primarySources) {
		this(null, primarySources);
	}

	/**
	 * Create a new {@link SpringApplication} instance. The application context will load
	 * beans from the specified primary sources (see {@link SpringApplication class-level}
	 * documentation for details). The instance can be customized before calling
	 * {@link #run(String...)}.
	 * @param resourceLoader the resource loader to use
	 * @param primarySources the primary bean sources
	 * @see #run(Class, String[])
	 * @see #setSources(Set)
	 */
	// SpringApplication 的核心构造函数，完成对象成员变量的赋初值
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public SpringApplication(ResourceLoader resourceLoader, Class<?>... primarySources) {
		this.resourceLoader = resourceLoader;
		// 校验并存储配置源：使用 Assert.notNull 确保 primarySources 不为空，并使用 LinkedHashSet 存储，保证有序且不重复。
		Assert.notNull(primarySources, "'primarySources' must not be null");
		this.primarySources = new LinkedHashSet<>(Arrays.asList(primarySources));
		// 推断 Web 应用类型
		// 如果类路径里只有 WebFlux 的类，选 REACTIVE。
		// 如果类路径里没有 Servlet 的类，选 NONE。
		// 如果都有，默认选 SERVLET（为了兼容性）。
		this.properties.setWebApplicationType(WebApplicationType.deduceFromClasspath());
		// 初始化引导注册表：通过 getSpringFactoriesInstances 方法从 META-INF/spring.factories（或 Spring 3.x 后的新位置）加载所有的 BootstrapRegistryInitializer
		this.bootstrapRegistryInitializers = new ArrayList<>(
				getSpringFactoriesInstances(BootstrapRegistryInitializer.class));
		// 加载上下文初始化器：加载所有配置的 ApplicationContextInitializer 并存入 initializers 列表。
		setInitializers((Collection) getSpringFactoriesInstances(ApplicationContextInitializer.class));
		// 加载监听器：加载所有配置的 ApplicationListener 并存入 listeners 列表。
		setListeners((Collection) getSpringFactoriesInstances(ApplicationListener.class));
		// 推断主启动类：调用 deduceMainApplicationClass() 来寻找是谁启动了程序。
		this.mainApplicationClass = deduceMainApplicationClass();
	}

	private Class<?> deduceMainApplicationClass() {
		// 获取 Java 堆栈走路器（StackWalker）实例
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
			// 开始遍历当前的线程方法调用栈
			.walk(this::findMainClass)
			.orElse(null);
	}

	private Optional<Class<?>> findMainClass(Stream<StackFrame> stack) {
		// 检查栈帧中的方法名是否为 "main"。
		return stack.filter((frame) -> Objects.equals(frame.getMethodName(), "main"))
			.findFirst()
			// 提取类信息。
			.map(StackWalker.StackFrame::getDeclaringClass);
	}

	/**
	 * Run the Spring application, creating and refreshing a new
	 * {@link ApplicationContext}.
	 * @param args the application arguments (usually passed from a Java main method)
	 * @return a running {@link ApplicationContext}
	 */

	// 最核心的方法：run 方法。
	// 它定义了 Spring Boot 应用从零到运行的全生命周期流程。
	public ConfigurableApplicationContext run(String... args) {
		// 开启启动计时器。这用于最后计算“应用在多长时间内启动完成”。
		Startup startup = Startup.create();
		// 如果配置允许（默认开启），则准备向 JVM 注册关闭钩子。当进程被终止时，它负责优雅地关闭 Spring 容器。
		if (this.properties.isRegisterShutdownHook()) {
			SpringApplication.shutdownHook.enableShutdownHookAddition();
		}
		// 创建引导上下文。
		// 这主要用于在应用启动的最早期（环境还没准备好之前）处理一些临时的加密解密或共享逻辑
		DefaultBootstrapContext bootstrapContext = createBootstrapContext();
		ConfigurableApplicationContext context = null;
		// 设置 java.awt.headless。确保在没有显卡或显示器的服务器环境下，图形相关的底层类库不会报错。
		configureHeadlessProperty();
		// 从 spring.factories 加载所有的 SpringApplicationRunListener
		SpringApplicationRunListeners listeners = getRunListeners(args);
		// 发布第一个事件。
		// 通知所有监听器：Spring Boot 准备开始打火了！此时环境（Environment）和上下文（Context）都还没创建。
		listeners.starting(bootstrapContext, this.mainApplicationClass);
		try {
			// 将原始的命令行 String[] 数组封装成更易操作的对象。
			ApplicationArguments applicationArguments = new DefaultApplicationArguments(args);
			// 非常关键的一步
			ConfigurableEnvironment environment = prepareEnvironment(listeners, bootstrapContext, applicationArguments);
			// 在控制台打印出那个熟悉的 "Spring" 字符画
			Banner printedBanner = printBanner(environment);
			// 核心工厂方法。
			// 根据推断的应用类型，实例化具体的 ApplicationContext 类（如 AnnotationConfigServletWebServerApplicationContext）
			context = createApplicationContext();
			context.setApplicationStartup(this.applicationStartup);
			// 容器刷新前的初始化
			// 将环境对象注入容器。
			// 应用所有的 ApplicationContextInitializer。
			// 注册启动类（Primary Sources）到容器中，以便后续扫描。
			// 通知监听器容器已准备好（contextPrepared 和 contextLoaded 事件）。
			prepareContext(bootstrapContext, context, environment, listeners, applicationArguments, printedBanner);
			// 调用 Spring 核心的 refresh() 方法
			refreshContext(context);
			// 默认是空实现，留给子类扩展刷新后的逻辑
			afterRefresh(context, applicationArguments);
			startup.started();
			// 在日志中打印 Started XxxApplication in Y.YYY seconds
			if (this.properties.isLogStartupInfo()) {
				new StartupInfoLogger(this.mainApplicationClass, environment).logStarted(getApplicationLog(), startup);
			}
			// 发布事件。告诉所有人容器已经刷新完成，可以正常工作了。
			listeners.started(context, startup.timeTakenToStarted());
			// 寻找并执行容器中所有的 CommandLineRunner 和 ApplicationRunner。
			// 这是用户自定义启动后立即执行业务逻辑的地方。
			callRunners(context, applicationArguments);
		}
		catch (Throwable ex) {
			throw handleRunFailure(context, ex, listeners);
		}
		try {
			if (context.isRunning()) {
				listeners.ready(context, startup.ready());
			}
		}
		catch (Throwable ex) {
			throw handleRunFailure(context, ex, null);
		}
		return context;
	}
	// SpringApplication.run() 方法中“第一阶段”的核心。
	// 它完成了引导上下文的从无到有以及初始化加载。
	private DefaultBootstrapContext createBootstrapContext() {
		// 实例化一个空的引导上下文容器。
		DefaultBootstrapContext bootstrapContext = new DefaultBootstrapContext();
		// 遍历并执行所有已加载的初始化器。
		this.bootstrapRegistryInitializers.forEach((initializer) -> initializer.initialize(bootstrapContext));
		return bootstrapContext;
	}
	// SpringApplication.run() 方法中至关重要的一步：准备环境 (Environment)。
	// 它决定了应用将读取哪些配置（如数据库地址、端口号），以及如何解析这些配置。
	private ConfigurableEnvironment prepareEnvironment(SpringApplicationRunListeners listeners,
			DefaultBootstrapContext bootstrapContext, ApplicationArguments applicationArguments) {
		// Create and configure the environment
		// 根据构造函数中推断的应用类型（Servlet、Reactive 或 None），创建一个具体的 ConfigurableEnvironment 实例（如 StandardServletEnvironment）。
		ConfigurableEnvironment environment = getOrCreateEnvironment();
		// 预配置步骤。
		// 它会将命令行参数（args）封装成一个 PropertySource 放入环境中，并根据需要激活特定的 Profile。
		configureEnvironment(environment, applicationArguments.getSourceArgs());
		ConfigurationPropertySources.attach(environment);
		// 发布环境就绪事件 (关键节点)
		// 重大意义：这是 application.yaml 或 application.properties 被加载的真正时机。
		// Spring Boot 内部的 ConfigDataEnvironmentPostProcessor 会监听到这个事件，并开始扫描类路径下的配置文件。
		listeners.environmentPrepared(bootstrapContext, environment);
		// 细节：ApplicationInfo（主类信息）和 DefaultProperties（代码设置的默认值）应该具有最低优先级。
		// 将它们移动到 PropertySources 列表的末尾，确保它们不会覆盖用户在配置文件中定义的同名属性。
		ApplicationInfoPropertySource.moveToEnd(environment);
		DefaultPropertiesPropertySource.moveToEnd(environment);
		Assert.state(!environment.containsProperty("spring.main.environment-prefix"),
				"Environment prefix cannot be set via properties.");
		// 反射赋值
		// 将环境中以 spring.main 开头的配置直接注入到当前的 SpringApplication 实例中。
		// 例如，你在 yml 里写了 spring.main.banner-mode: off，执行到这一行时，当前 SpringApplication 对象的 bannerMode 属性就会被改为 OFF。
		bindToSpringApplication(environment);
		// 如果开发者手动注入了一个标准的 StandardEnvironment，但 Spring Boot 检测到这其实是一个 Web 应用，转换器会将其包装或转换为正确的 WebEnvironment 子类。
		if (!this.isCustomEnvironment) {
			EnvironmentConverter environmentConverter = new EnvironmentConverter(getClassLoader());
			environment = environmentConverter.convertEnvironmentIfNecessary(environment, deduceEnvironmentClass());
		}
		ConfigurationPropertySources.attach(environment);
		return environment;
	}

	private Class<? extends ConfigurableEnvironment> deduceEnvironmentClass() {
		WebApplicationType webApplicationType = this.properties.getWebApplicationType();
		Class<? extends ConfigurableEnvironment> environmentType = this.applicationContextFactory
			.getEnvironmentType(webApplicationType);
		if (environmentType == null && this.applicationContextFactory != ApplicationContextFactory.DEFAULT) {
			environmentType = ApplicationContextFactory.DEFAULT.getEnvironmentType(webApplicationType);
		}
		return (environmentType != null) ? environmentType : ApplicationEnvironment.class;
	}
	// prepareContext 方法是 Spring Boot 启动过程中的“装配车间”。
	// 它的核心任务是将之前准备好的所有“零件”（环境、参数、监听器、Banner）正确地安装到那个刚创建出来的“空壳”容器（ApplicationContext）中。

	private void prepareContext(DefaultBootstrapContext bootstrapContext, ConfigurableApplicationContext context,
			ConfigurableEnvironment environment, SpringApplicationRunListeners listeners,
			ApplicationArguments applicationArguments, Banner printedBanner) {
		// 将之前 prepareEnvironment 阶段打磨好的环境对象注入容器。
		// 从此容器中的 Bean 就能通过 @Value 或 Environment 接口获取配置了。
		context.setEnvironment(environment);
		// 执行一些基础的容器后处理。例如，如果用户设置了 beanNameGenerator（Bean 名称生成器）或 resourceLoader（资源加载器），会在这一步应用到容器中。
		postProcessApplicationContext(context);
		addAotGeneratedInitializerIfNecessary(this.initializers);
		// 遍历并执行所有的 ApplicationContextInitializer。这是开发者在容器刷新前修改容器状态（如添加自定义监听器）的最后机会
		applyInitializers(context);
		// 发布事件。通知监听器：容器的“地基”已经打好了。
		listeners.contextPrepared(context);
		// 关键转折点。关闭引导上下文，并触发 BootstrapContextClosedEvent。此时，在引导期间创建的对象（如加解密工具）会在此处完成向正式容器的“交接棒”。
		bootstrapContext.close(context);
		// 启动日志打印：如果 isLogStartupInfo 为 true，控制台会输出应用启动的基本信息（如 PID、运行路径）以及激活的 Profiles。
		if (this.properties.isLogStartupInfo()) {
			logStartupInfo(context.getParent() == null);
			logStartupInfo(context);
			logStartupProfileInfo(context);
		}
		// Add boot specific singleton beans
		// 将命令行参数 applicationArguments 注册为名为 springApplicationArguments 的 Bean。
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		beanFactory.registerSingleton("springApplicationArguments", applicationArguments);
		// 将 Banner 对象注册为 springBootBanner
		if (printedBanner != null) {
			beanFactory.registerSingleton("springBootBanner", printedBanner);
		}
		if (beanFactory instanceof AbstractAutowireCapableBeanFactory autowireCapableBeanFactory) {
			autowireCapableBeanFactory.setAllowCircularReferences(this.properties.isAllowCircularReferences());
			if (beanFactory instanceof DefaultListableBeanFactory listableBeanFactory) {
				listableBeanFactory.setAllowBeanDefinitionOverriding(this.properties.isAllowBeanDefinitionOverriding());
			}
		}
		// 全局懒加载：如果开启了 lazyInitialization，会向容器添加一个 BeanFactoryPostProcessor，将所有非延迟加载的 Bean 全部改为懒加载。
		if (this.properties.isLazyInitialization()) {
			context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());
		}
		if (this.properties.isKeepAlive()) {
			context.addApplicationListener(new KeepAlive());
		}
		context.addBeanFactoryPostProcessor(new PropertySourceOrderingBeanFactoryPostProcessor(context));
		if (!AotDetector.useGeneratedArtifacts()) {
			// Load the sources
			// 获取所有的配置源（通常就是那个带有 @SpringBootApplication 注解的主类）。
			Set<Object> sources = getAllSources();
			Assert.state(!ObjectUtils.isEmpty(sources), "No sources defined");
			// 核心动作。将主类作为“种子”注册到容器的 BeanDefinitionRegistry 中。
			// 为什么重要？ 只有注册了主类，Spring 才能根据主类上的注解开始后续的组件扫描（Component Scan）。
			load(context, sources.toArray(new Object[0]));
		}
		// 发布事件。通知所有监听器：容器已经装配完毕，所有基本的配置源都已经加载进去了。
		listeners.contextLoaded(context);
	}

	private void addAotGeneratedInitializerIfNecessary(List<ApplicationContextInitializer<?>> initializers) {
		if (AotDetector.useGeneratedArtifacts()) {
			List<ApplicationContextInitializer<?>> aotInitializers = new ArrayList<>(
					initializers.stream().filter(AotApplicationContextInitializer.class::isInstance).toList());
			if (aotInitializers.isEmpty()) {
				String initializerClassName = this.mainApplicationClass.getName() + "__ApplicationContextInitializer";
				if (!ClassUtils.isPresent(initializerClassName, getClassLoader())) {
					throw new AotInitializerNotFoundException(this.mainApplicationClass, initializerClassName);
				}
				aotInitializers.add(AotApplicationContextInitializer.forInitializerClasses(initializerClassName));
			}
			initializers.removeAll(aotInitializers);
			initializers.addAll(0, aotInitializers);
		}
	}

	private void refreshContext(ConfigurableApplicationContext context) {
		if (this.properties.isRegisterShutdownHook()) {
			shutdownHook.registerApplicationContext(context);
		}
		refresh(context);
	}
	// 核心目的是确保 Java AWT（Abstract Window Toolkit）在服务器环境（无显示器）下能够正常初始化
	private void configureHeadlessProperty() {
		System.setProperty(SYSTEM_PROPERTY_JAVA_AWT_HEADLESS,
				System.getProperty(SYSTEM_PROPERTY_JAVA_AWT_HEADLESS, Boolean.toString(this.headless)));
	}
	// 负责加载并初始化 Spring Boot 启动过程中的“广播站”——SpringApplicationRunListeners。
	// 它是观察者模式的核心实现，负责在启动的各个关键节点（如开始启动、环境准备好、容器准备好等）通知所有注册的监听器。
	private SpringApplicationRunListeners getRunListeners(String[] args) {
		// 构建参数解析器 (ArgumentResolver)
		ArgumentResolver argumentResolver = ArgumentResolver.of(SpringApplication.class, this);
		argumentResolver = argumentResolver.and(String[].class, args);
		// 从工厂加载监听器
		// 典型的 Spring Boot 扩展机制。它会去类路径下的 META-INF/spring.factories（或新的 META-INF/spring/org.springframework.boot.SpringApplicationRunListener.imports）文件中，寻找所有配置好的监听器实现类。
		// 默认实现：最著名的默认实现是 EventPublishingRunListener，它负责将这些启动信号转化为标准的 Spring 事件（ApplicationEvent）。
		List<SpringApplicationRunListener> listeners = getSpringFactoriesInstances(SpringApplicationRunListener.class,
				argumentResolver);
		// 高级扩展或测试提供的“后门”。如果在当前线程的 ThreadLocal 中设置了 SpringApplicationHook，它会从中提取一个额外的监听器并加入队列。
		SpringApplicationHook hook = applicationHook.get();
		SpringApplicationRunListener hookListener = (hook != null) ? hook.getRunListener(this) : null;
		if (hookListener != null) {
			listeners = new ArrayList<>(listeners);
			listeners.add(hookListener);
		}
		// 将这一组监听器包装成一个 SpringApplicationRunListeners 对象（注意这里是复数 S）。
		// 目的：这是一种组合模式。之后在 run 方法中，只需要调用 listeners.starting()，这个包装类就会自动遍历内部所有的监听器并逐一触发。
		return new SpringApplicationRunListeners(logger, listeners, this.applicationStartup);
	}

	private <T> List<T> getSpringFactoriesInstances(Class<T> type) {
		return getSpringFactoriesInstances(type, null);
	}

	private <T> List<T> getSpringFactoriesInstances(Class<T> type, ArgumentResolver argumentResolver) {
		return SpringFactoriesLoader.forDefaultResourceLocation(getClassLoader()).load(type, argumentResolver);
	}
	// 揭示了 Spring Boot 如何根据不同的应用类型，精准地“定制”一套配置环境。它并不是简单地 new 一个 Environment 对象，而是通过工厂模式确保环境与应用的运行模式（Web、Reactive、Standard）完美匹配。
	private ConfigurableEnvironment getOrCreateEnvironment() {
		// 是否已经被手动设置过。如果开发者在启动前调用了 setEnvironment(...)，则直接返回该实例，体现了“约定优于配置，但人工优先”的原则。
		if (this.environment != null) {
			return this.environment;
		}
		// 获取的是我们在构造函数阶段推断出的类型（SERVLET、REACTIVE 或 NONE）。
		WebApplicationType webApplicationType = this.properties.getWebApplicationType();
		ConfigurableEnvironment environment = this.applicationContextFactory.createEnvironment(webApplicationType);
		if (environment == null && this.applicationContextFactory != ApplicationContextFactory.DEFAULT) {
			environment = ApplicationContextFactory.DEFAULT.createEnvironment(webApplicationType);
		}
		return (environment != null) ? environment : new ApplicationEnvironment();
	}

	/**
	 * Template method delegating to
	 * {@link #configurePropertySources(ConfigurableEnvironment, String[])} and
	 * {@link #configureProfiles(ConfigurableEnvironment, String[])} in that order.
	 * Override this method for complete control over Environment customization, or one of
	 * the above for fine-grained control over property sources or profiles, respectively.
	 * @param environment this application's environment
	 * @param args arguments passed to the {@code run} method
	 * @see #configureProfiles(ConfigurableEnvironment, String[])
	 * @see #configurePropertySources(ConfigurableEnvironment, String[])
	 */
	protected void configureEnvironment(ConfigurableEnvironment environment, String[] args) {
		if (this.addConversionService) {
			environment.setConversionService(new ApplicationConversionService());
		}
		configurePropertySources(environment, args);
		configureProfiles(environment, args);
	}

	/**
	 * Add, remove or re-order any {@link PropertySource}s in this application's
	 * environment.
	 * @param environment this application's environment
	 * @param args arguments passed to the {@code run} method
	 * @see #configureEnvironment(ConfigurableEnvironment, String[])
	 */
	protected void configurePropertySources(ConfigurableEnvironment environment, String[] args) {
		// 获取环境中存储配置的容器。这是一个持有 PropertySource 列表的对象，支持 addFirst、addLast 等操作来控制优先级。
		MutablePropertySources sources = environment.getPropertySources();
		// 如果开发者通过代码设置了 app.setDefaultProperties(map)，这些属性会被存入。
		if (!CollectionUtils.isEmpty(this.defaultProperties)) {
			DefaultPropertiesPropertySource.addOrMerge(this.defaultProperties, sources);
		}
		// 处理命令行参数 (Command Line Arguments)
		// 检查开启状态：只有当 addCommandLineProperties 为 true（默认值）且确实传入了参数时才处理。
		if (this.addCommandLineProperties && args.length > 0) {
			String name = CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME;
			if (sources.contains(name)) {
				PropertySource<?> source = sources.get(name);
				CompositePropertySource composite = new CompositePropertySource(name);
				composite
					.addPropertySource(new SimpleCommandLinePropertySource("springApplicationCommandLineArgs", args));
				composite.addPropertySource(source);
				sources.replace(name, composite);
			}
			else {
				sources.addFirst(new SimpleCommandLinePropertySource(args));
			}
		}
		// 将主类（Main Class）相关的信息（如类名）存入环境，方便后续通过 ${spring.main.class} 等方式引用。
		environment.getPropertySources().addLast(new ApplicationInfoPropertySource(this.mainApplicationClass));
	}

	/**
	 * Configure which profiles are active (or active by default) for this application
	 * environment. Additional profiles may be activated during configuration file
	 * processing through the {@code spring.profiles.active} property.
	 * @param environment this application's environment
	 * @param args arguments passed to the {@code run} method
	 * @see #configureEnvironment(ConfigurableEnvironment, String[])
	 */
	// 默认行为：Spring Boot 并不通过在这个方法里写死逻辑来激活 Profile。
	// 相反，它通过 EnvironmentPostProcessor（特别是 ConfigDataEnvironmentPostProcessor）来处理配置文件中的 spring.profiles.active 属性。
	// 预留扩展：它被声明为 protected，目的是让开发者可以通过继承 SpringApplication 来重写此方法，实现硬编码级别的 Profile 激活逻辑。
	protected void configureProfiles(ConfigurableEnvironment environment, String[] args) {
	}

	/**
	 * Bind the environment to the {@link ApplicationProperties}.
	 * @param environment the environment to bind
	 */
	// 利用 Binder（绑定器） 机制，实现了配置属性对启动类行为的直接控制。
	protected void bindToSpringApplication(ConfigurableEnvironment environment) {
		try {
			// 创建一个绑定器实例。
			// 原理：它会扫描当前 environment 中所有的 PropertySource（包括命令行、YAML、系统变量等），并利用之前提到的“松散绑定”能力（支持驼峰、短横线、大写等各种格式）。
			// .bind("spring.main", ...) 指定搜索前缀。
			// 绑定器会查找所有以 spring.main 开头的配置项。例如，如果在 application.yml 中定义了：
			// spring:
			//  main:
			//    banner-mode: "off"
			//    log-startup-info: false
			// 绑定器就能识别出这些配置。
			// 执行动作：它将从环境中找到的 spring.main.* 配置值，通过反射调用对应的 setter 方法，直接注入到 this.properties 的字段中。
			Binder.get(environment).bind("spring.main", Bindable.ofInstance(this.properties));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Cannot bind to SpringApplication", ex);
		}
	}

	private Banner printBanner(ConfigurableEnvironment environment) {
		if (this.properties.getBannerMode(environment) == Banner.Mode.OFF) {
			return null;
		}
		ResourceLoader resourceLoader = (this.resourceLoader != null) ? this.resourceLoader
				: new DefaultResourceLoader(null);
		SpringApplicationBannerPrinter bannerPrinter = new SpringApplicationBannerPrinter(resourceLoader, this.banner);
		if (this.properties.getBannerMode(environment) == Mode.LOG) {
			return bannerPrinter.print(environment, this.mainApplicationClass, logger);
		}
		return bannerPrinter.print(environment, this.mainApplicationClass, System.out);
	}

	/**
	 * Strategy method used to create the {@link ApplicationContext}. By default this
	 * method will respect any explicitly set application context class or factory before
	 * falling back to a suitable default.
	 * @return the application context (not yet refreshed)
	 * @see #setApplicationContextFactory(ApplicationContextFactory)
	 */
	// Spring Boot 启动流程中的“生命大爆炸”时刻。
	// 虽然只有一行代码，但它标志着 Spring 容器（ApplicationContext）实例正式诞生。
	protected ConfigurableApplicationContext createApplicationContext() {
		return this.applicationContextFactory.create(this.properties.getWebApplicationType());
	}

	/**
	 * Apply any relevant post-processing to the {@link ApplicationContext}. Subclasses
	 * can apply additional processing as required.
	 * @param context the application context
	 */
	protected void postProcessApplicationContext(ConfigurableApplicationContext context) {
		if (this.beanNameGenerator != null) {
			context.getBeanFactory()
				.registerSingleton(AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR, this.beanNameGenerator);
		}
		if (this.resourceLoader != null) {
			if (context instanceof GenericApplicationContext genericApplicationContext) {
				genericApplicationContext.setResourceLoader(this.resourceLoader);
			}
			if (context instanceof DefaultResourceLoader defaultResourceLoader) {
				defaultResourceLoader.setClassLoader(this.resourceLoader.getClassLoader());
			}
		}
		if (this.addConversionService) {
			context.getBeanFactory().setConversionService(context.getEnvironment().getConversionService());
		}
	}

	/**
	 * Apply any {@link ApplicationContextInitializer}s to the context before it is
	 * refreshed.
	 * @param context the configured ApplicationContext (not refreshed yet)
	 * @see ConfigurableApplicationContext#refresh()
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected void applyInitializers(ConfigurableApplicationContext context) {
		for (ApplicationContextInitializer initializer : getInitializers()) {
			Class<?> requiredType = GenericTypeResolver.resolveTypeArgument(initializer.getClass(),
					ApplicationContextInitializer.class);
			Assert.state(requiredType.isInstance(context), "Unable to call initializer");
			initializer.initialize(context);
		}
	}

	/**
	 * Called to log startup information, subclasses may override to add additional
	 * logging.
	 * @param context the application context
	 * @since 3.4.0
	 */
	protected void logStartupInfo(ConfigurableApplicationContext context) {
		boolean isRoot = context.getParent() == null;
		if (isRoot) {
			new StartupInfoLogger(this.mainApplicationClass, context.getEnvironment()).logStarting(getApplicationLog());
		}
	}

	/**
	 * Called to log startup information, subclasses may override to add additional
	 * logging.
	 * @param isRoot true if this application is the root of a context hierarchy
	 * @deprecated since 3.4.0 for removal in 4.0.0 in favor of
	 * {@link #logStartupInfo(ConfigurableApplicationContext)}
	 */
	@Deprecated(since = "3.4.0", forRemoval = true)
	protected void logStartupInfo(boolean isRoot) {
	}

	/**
	 * Called to log active profile information.
	 * @param context the application context
	 */
	protected void logStartupProfileInfo(ConfigurableApplicationContext context) {
		Log log = getApplicationLog();
		if (log.isInfoEnabled()) {
			List<String> activeProfiles = quoteProfiles(context.getEnvironment().getActiveProfiles());
			if (ObjectUtils.isEmpty(activeProfiles)) {
				List<String> defaultProfiles = quoteProfiles(context.getEnvironment().getDefaultProfiles());
				String message = String.format("%s default %s: ", defaultProfiles.size(),
						(defaultProfiles.size() <= 1) ? "profile" : "profiles");
				log.info("No active profile set, falling back to " + message
						+ StringUtils.collectionToDelimitedString(defaultProfiles, ", "));
			}
			else {
				String message = (activeProfiles.size() == 1) ? "1 profile is active: "
						: activeProfiles.size() + " profiles are active: ";
				log.info("The following " + message + StringUtils.collectionToDelimitedString(activeProfiles, ", "));
			}
		}
	}

	private List<String> quoteProfiles(String[] profiles) {
		return Arrays.stream(profiles).map((profile) -> "\"" + profile + "\"").toList();
	}

	/**
	 * Returns the {@link Log} for the application. By default will be deduced.
	 * @return the application log
	 */
	protected Log getApplicationLog() {
		if (this.mainApplicationClass == null) {
			return logger;
		}
		return LogFactory.getLog(this.mainApplicationClass);
	}

	/**
	 * Load beans into the application context.
	 * @param context the context to load beans into
	 * @param sources the sources to load
	 */
	// 将“种子类”（Sources）加载到容器的 Bean 定义注册表中。
	// 简单来说，虽然容器（ApplicationContext）已经创建了，但它目前还是个“空壳”，并不知道该去哪里扫描 Bean。
	// load 方法的作用就是把你的启动类（通常是标注了 @SpringBootApplication 的类）注册进去，作为后续自动扫描的源头。
	protected void load(ApplicationContext context, Object[] sources) {
		if (logger.isDebugEnabled()) {
			logger.debug("Loading source " + StringUtils.arrayToCommaDelimitedString(sources));
		}
		// 从当前上下文中提取出 BeanDefinitionRegistry。这是 Spring 存放所有 Bean 定义（元数据）的底层仓库。
		// createBeanDefinitionLoader(...)：这是 Spring Boot 内部的一个多功能加载器。
		//强大的兼容性：它不仅支持加载 Java 配置类（标注了 @Configuration 的类），还支持加载 XML 配置文件、Groovy 脚本，甚至是具体的 Package 包路径。
		BeanDefinitionLoader loader = createBeanDefinitionLoader(getBeanDefinitionRegistry(context), sources);
		// 在真正开始加载之前，Spring 将环境信息和资源处理器注入到 loader 中：
		if (this.beanNameGenerator != null) {
			loader.setBeanNameGenerator(this.beanNameGenerator);
		}
		if (this.resourceLoader != null) {
			loader.setResourceLoader(this.resourceLoader);
		}
		if (this.environment != null) {
			loader.setEnvironment(this.environment);
		}
		// 最终的动作。它会将传入的 sources（通常是你的 MainApplication 类）解析为 BeanDefinition 并存入注册表。
		loader.load();
	}

	/**
	 * The ResourceLoader that will be used in the ApplicationContext.
	 * @return the resourceLoader the resource loader that will be used in the
	 * ApplicationContext (or null if the default)
	 */
	public ResourceLoader getResourceLoader() {
		return this.resourceLoader;
	}

	/**
	 * Either the ClassLoader that will be used in the ApplicationContext (if
	 * {@link #setResourceLoader(ResourceLoader) resourceLoader} is set), or the context
	 * class loader (if not null), or the loader of the Spring {@link ClassUtils} class.
	 * @return a ClassLoader (never null)
	 */
	public ClassLoader getClassLoader() {
		if (this.resourceLoader != null) {
			return this.resourceLoader.getClassLoader();
		}
		return ClassUtils.getDefaultClassLoader();
	}

	/**
	 * Get the bean definition registry.
	 * @param context the application context
	 * @return the BeanDefinitionRegistry if it can be determined
	 */
	private BeanDefinitionRegistry getBeanDefinitionRegistry(ApplicationContext context) {
		if (context instanceof BeanDefinitionRegistry registry) {
			return registry;
		}
		if (context instanceof AbstractApplicationContext abstractApplicationContext) {
			return (BeanDefinitionRegistry) abstractApplicationContext.getBeanFactory();
		}
		throw new IllegalStateException("Could not locate BeanDefinitionRegistry");
	}

	/**
	 * Factory method used to create the {@link BeanDefinitionLoader}.
	 * @param registry the bean definition registry
	 * @param sources the sources to load
	 * @return the {@link BeanDefinitionLoader} that will be used to load beans
	 */
	protected BeanDefinitionLoader createBeanDefinitionLoader(BeanDefinitionRegistry registry, Object[] sources) {
		return new BeanDefinitionLoader(registry, sources);
	}

	/**
	 * Refresh the underlying {@link ApplicationContext}.
	 * @param applicationContext the application context to refresh
	 */
	protected void refresh(ConfigurableApplicationContext applicationContext) {
		applicationContext.refresh();
	}

	/**
	 * Called after the context has been refreshed.
	 * @param context the application context
	 * @param args the application arguments
	 */
	// afterRefresh 是一个空的钩子方法（Hook Method）。它标志着 Spring 容器的“核心生命周期”已经全部完成。
	protected void afterRefresh(ConfigurableApplicationContext context, ApplicationArguments args) {
	}
	// 标志着 Spring Boot 启动流程的“最后一公里”。
	// 当容器已经刷新（Refresh）完毕、所有的 Bean 都已经实例化、内嵌服务器也已经启动后，Spring Boot 会通过 callRunners 执行那些需要在应用启动后立即运行的自定义逻辑。
	private void callRunners(ConfigurableApplicationContext context, ApplicationArguments args) {
		// 作用：在容器中查找所有类型为 Runner 的 Bean。
		// 背景知识：在 Spring Boot 中，Runner 是一个内部标记接口，它有两个主要的子接口：
		//CommandLineRunner：参数为原始的 String[] args。
		//ApplicationRunner：参数为封装过的 ApplicationArguments。
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		String[] beanNames = beanFactory.getBeanNamesForType(Runner.class);
		Map<Runner, String> instancesToBeanNames = new IdentityHashMap<>();
		for (String beanName : beanNames) {
			instancesToBeanNames.put(beanFactory.getBean(beanName, Runner.class), beanName);
		}
		// 创建一个支持优先级排序的比较器。
		// 核心机制：Spring Boot 支持通过 @Order(n) 注解或实现 Ordered 接口来控制多个 Runner 的执行顺序。数字越小，优先级越高。
		Comparator<Object> comparator = getOrderComparator(beanFactory)
			.withSourceProvider(new FactoryAwareOrderSourceProvider(beanFactory, instancesToBeanNames));
		// 按照排好的顺序，依次调用每个 Runner 的 run 方法。
		instancesToBeanNames.keySet().stream().sorted(comparator).forEach((runner) -> callRunner(runner, args));
	}

	private OrderComparator getOrderComparator(ConfigurableListableBeanFactory beanFactory) {
		Comparator<?> dependencyComparator = (beanFactory instanceof DefaultListableBeanFactory defaultListableBeanFactory)
				? defaultListableBeanFactory.getDependencyComparator() : null;
		return (dependencyComparator instanceof OrderComparator orderComparator) ? orderComparator
				: AnnotationAwareOrderComparator.INSTANCE;
	}
	// Spring Boot 如何对两种不同类型的 Runner 进行分发调用。虽然它们都被统一称为“运行器”，但处理参数的方式略有不同。
	private void callRunner(Runner runner, ApplicationArguments args) {
		if (runner instanceof ApplicationRunner) {
			callRunner(ApplicationRunner.class, runner, (applicationRunner) -> applicationRunner.run(args));
		}
		if (runner instanceof CommandLineRunner) {
			callRunner(CommandLineRunner.class, runner,
					(commandLineRunner) -> commandLineRunner.run(args.getSourceArgs()));
		}
	}

	@SuppressWarnings("unchecked")
	private <R extends Runner> void callRunner(Class<R> type, Runner runner, ThrowingConsumer<R> call) {
		call.throwing(
				(message, ex) -> new IllegalStateException("Failed to execute " + ClassUtils.getShortName(type), ex))
			.accept((R) runner);
	}

	private RuntimeException handleRunFailure(ConfigurableApplicationContext context, Throwable exception,
			SpringApplicationRunListeners listeners) {
		if (exception instanceof AbandonedRunException abandonedRunException) {
			return abandonedRunException;
		}
		try {
			try {
				handleExitCode(context, exception);
				if (listeners != null) {
					listeners.failed(context, exception);
				}
			}
			finally {
				reportFailure(getExceptionReporters(context), exception);
				if (context != null) {
					context.close();
					shutdownHook.deregisterFailedApplicationContext(context);
				}
			}
		}
		catch (Exception ex) {
			logger.warn("Unable to close ApplicationContext", ex);
		}
		return (exception instanceof RuntimeException runtimeException) ? runtimeException
				: new IllegalStateException(exception);
	}

	private Collection<SpringBootExceptionReporter> getExceptionReporters(ConfigurableApplicationContext context) {
		try {
			ArgumentResolver argumentResolver = ArgumentResolver.of(ConfigurableApplicationContext.class, context);
			return getSpringFactoriesInstances(SpringBootExceptionReporter.class, argumentResolver);
		}
		catch (Throwable ex) {
			return Collections.emptyList();
		}
	}

	private void reportFailure(Collection<SpringBootExceptionReporter> exceptionReporters, Throwable failure) {
		try {
			for (SpringBootExceptionReporter reporter : exceptionReporters) {
				if (reporter.reportException(failure)) {
					registerLoggedException(failure);
					return;
				}
			}
		}
		catch (Throwable ex) {
			// Continue with normal handling of the original failure
		}
		if (logger.isErrorEnabled()) {
			if (NativeDetector.inNativeImage()) {
				// Depending on how early the failure was, logging may not work in a
				// native image so we output the stack trace directly to System.out
				// instead.
				System.out.println("Application run failed");
				failure.printStackTrace(System.out);
			}
			else {
				logger.error("Application run failed", failure);
			}
			registerLoggedException(failure);
		}
	}

	/**
	 * Register that the given exception has been logged. By default, if the running in
	 * the main thread, this method will suppress additional printing of the stacktrace.
	 * @param exception the exception that was logged
	 */
	protected void registerLoggedException(Throwable exception) {
		SpringBootExceptionHandler handler = getSpringBootExceptionHandler();
		if (handler != null) {
			handler.registerLoggedException(exception);
		}
	}

	private void handleExitCode(ConfigurableApplicationContext context, Throwable exception) {
		int exitCode = getExitCodeFromException(context, exception);
		if (exitCode != 0) {
			if (context != null) {
				context.publishEvent(new ExitCodeEvent(context, exitCode));
			}
			SpringBootExceptionHandler handler = getSpringBootExceptionHandler();
			if (handler != null) {
				handler.registerExitCode(exitCode);
			}
		}
	}

	private int getExitCodeFromException(ConfigurableApplicationContext context, Throwable exception) {
		int exitCode = getExitCodeFromMappedException(context, exception);
		if (exitCode == 0) {
			exitCode = getExitCodeFromExitCodeGeneratorException(exception);
		}
		return exitCode;
	}

	private int getExitCodeFromMappedException(ConfigurableApplicationContext context, Throwable exception) {
		if (context == null || !context.isActive()) {
			return 0;
		}
		ExitCodeGenerators generators = new ExitCodeGenerators();
		Collection<ExitCodeExceptionMapper> beans = context.getBeansOfType(ExitCodeExceptionMapper.class).values();
		generators.addAll(exception, beans);
		return generators.getExitCode();
	}

	private int getExitCodeFromExitCodeGeneratorException(Throwable exception) {
		if (exception == null) {
			return 0;
		}
		if (exception instanceof ExitCodeGenerator generator) {
			return generator.getExitCode();
		}
		return getExitCodeFromExitCodeGeneratorException(exception.getCause());
	}

	SpringBootExceptionHandler getSpringBootExceptionHandler() {
		if (isMainThread(Thread.currentThread())) {
			return SpringBootExceptionHandler.forCurrentThread();
		}
		return null;
	}

	private boolean isMainThread(Thread currentThread) {
		return ("main".equals(currentThread.getName()) || "restartedMain".equals(currentThread.getName()))
				&& "main".equals(currentThread.getThreadGroup().getName());
	}

	/**
	 * Returns the main application class that has been deduced or explicitly configured.
	 * @return the main application class or {@code null}
	 */
	public Class<?> getMainApplicationClass() {
		return this.mainApplicationClass;
	}

	/**
	 * Set a specific main application class that will be used as a log source and to
	 * obtain version information. By default the main application class will be deduced.
	 * Can be set to {@code null} if there is no explicit application class.
	 * @param mainApplicationClass the mainApplicationClass to set or {@code null}
	 */
	public void setMainApplicationClass(Class<?> mainApplicationClass) {
		this.mainApplicationClass = mainApplicationClass;
	}

	/**
	 * Returns the type of web application that is being run.
	 * @return the type of web application
	 * @since 2.0.0
	 */
	public WebApplicationType getWebApplicationType() {
		return this.properties.getWebApplicationType();
	}

	/**
	 * Sets the type of web application to be run. If not explicitly set the type of web
	 * application will be deduced based on the classpath.
	 * @param webApplicationType the web application type
	 * @since 2.0.0
	 */
	public void setWebApplicationType(WebApplicationType webApplicationType) {
		Assert.notNull(webApplicationType, "'webApplicationType' must not be null");
		this.properties.setWebApplicationType(webApplicationType);
	}

	/**
	 * Sets if bean definition overriding, by registering a definition with the same name
	 * as an existing definition, should be allowed. Defaults to {@code false}.
	 * @param allowBeanDefinitionOverriding if overriding is allowed
	 * @since 2.1.0
	 * @see DefaultListableBeanFactory#setAllowBeanDefinitionOverriding(boolean)
	 */
	public void setAllowBeanDefinitionOverriding(boolean allowBeanDefinitionOverriding) {
		this.properties.setAllowBeanDefinitionOverriding(allowBeanDefinitionOverriding);
	}

	/**
	 * Sets whether to allow circular references between beans and automatically try to
	 * resolve them. Defaults to {@code false}.
	 * @param allowCircularReferences if circular references are allowed
	 * @since 2.6.0
	 * @see AbstractAutowireCapableBeanFactory#setAllowCircularReferences(boolean)
	 */
	public void setAllowCircularReferences(boolean allowCircularReferences) {
		this.properties.setAllowCircularReferences(allowCircularReferences);
	}

	/**
	 * Sets if beans should be initialized lazily. Defaults to {@code false}.
	 * @param lazyInitialization if initialization should be lazy
	 * @since 2.2
	 * @see BeanDefinition#setLazyInit(boolean)
	 */
	public void setLazyInitialization(boolean lazyInitialization) {
		this.properties.setLazyInitialization(lazyInitialization);
	}

	/**
	 * Sets if the application is headless and should not instantiate AWT. Defaults to
	 * {@code true} to prevent java icons appearing.
	 * @param headless if the application is headless
	 */
	public void setHeadless(boolean headless) {
		this.headless = headless;
	}

	/**
	 * Sets if the created {@link ApplicationContext} should have a shutdown hook
	 * registered. Defaults to {@code true} to ensure that JVM shutdowns are handled
	 * gracefully.
	 * @param registerShutdownHook if the shutdown hook should be registered
	 * @see #getShutdownHandlers()
	 */
	public void setRegisterShutdownHook(boolean registerShutdownHook) {
		this.properties.setRegisterShutdownHook(registerShutdownHook);
	}

	/**
	 * Sets the {@link Banner} instance which will be used to print the banner when no
	 * static banner file is provided.
	 * @param banner the Banner instance to use
	 */
	public void setBanner(Banner banner) {
		this.banner = banner;
	}

	/**
	 * Sets the mode used to display the banner when the application runs. Defaults to
	 * {@code Banner.Mode.CONSOLE}.
	 * @param bannerMode the mode used to display the banner
	 */
	public void setBannerMode(Banner.Mode bannerMode) {
		this.properties.setBannerMode(bannerMode);
	}

	/**
	 * Sets if the application information should be logged when the application starts.
	 * Defaults to {@code true}.
	 * @param logStartupInfo if startup info should be logged.
	 */
	public void setLogStartupInfo(boolean logStartupInfo) {
		this.properties.setLogStartupInfo(logStartupInfo);
	}

	/**
	 * Sets if a {@link CommandLinePropertySource} should be added to the application
	 * context in order to expose arguments. Defaults to {@code true}.
	 * @param addCommandLineProperties if command line arguments should be exposed
	 */
	public void setAddCommandLineProperties(boolean addCommandLineProperties) {
		this.addCommandLineProperties = addCommandLineProperties;
	}

	/**
	 * Sets if the {@link ApplicationConversionService} should be added to the application
	 * context's {@link Environment}.
	 * @param addConversionService if the application conversion service should be added
	 * @since 2.1.0
	 */
	public void setAddConversionService(boolean addConversionService) {
		this.addConversionService = addConversionService;
	}

	/**
	 * Adds {@link BootstrapRegistryInitializer} instances that can be used to initialize
	 * the {@link BootstrapRegistry}.
	 * @param bootstrapRegistryInitializer the bootstrap registry initializer to add
	 * @since 2.4.5
	 */
	public void addBootstrapRegistryInitializer(BootstrapRegistryInitializer bootstrapRegistryInitializer) {
		Assert.notNull(bootstrapRegistryInitializer, "'bootstrapRegistryInitializer' must not be null");
		this.bootstrapRegistryInitializers.addAll(Arrays.asList(bootstrapRegistryInitializer));
	}

	/**
	 * Set default environment properties which will be used in addition to those in the
	 * existing {@link Environment}.
	 * @param defaultProperties the additional properties to set
	 */
	public void setDefaultProperties(Map<String, Object> defaultProperties) {
		this.defaultProperties = defaultProperties;
	}

	/**
	 * Convenient alternative to {@link #setDefaultProperties(Map)}.
	 * @param defaultProperties some {@link Properties}
	 */
	public void setDefaultProperties(Properties defaultProperties) {
		this.defaultProperties = new HashMap<>();
		for (Object key : Collections.list(defaultProperties.propertyNames())) {
			this.defaultProperties.put((String) key, defaultProperties.get(key));
		}
	}

	/**
	 * Set additional profile values to use (on top of those set in system or command line
	 * properties).
	 * @param profiles the additional profiles to set
	 */
	public void setAdditionalProfiles(String... profiles) {
		this.additionalProfiles = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(profiles)));
	}

	/**
	 * Return an immutable set of any additional profiles in use.
	 * @return the additional profiles
	 */
	public Set<String> getAdditionalProfiles() {
		return this.additionalProfiles;
	}

	/**
	 * Sets the bean name generator that should be used when generating bean names.
	 * @param beanNameGenerator the bean name generator
	 */
	public void setBeanNameGenerator(BeanNameGenerator beanNameGenerator) {
		this.beanNameGenerator = beanNameGenerator;
	}

	/**
	 * Sets the underlying environment that should be used with the created application
	 * context.
	 * @param environment the environment
	 */
	public void setEnvironment(ConfigurableEnvironment environment) {
		this.isCustomEnvironment = true;
		this.environment = environment;
	}

	/**
	 * Add additional items to the primary sources that will be added to an
	 * ApplicationContext when {@link #run(String...)} is called.
	 * <p>
	 * The sources here are added to those that were set in the constructor. Most users
	 * should consider using {@link #getSources()}/{@link #setSources(Set)} rather than
	 * calling this method.
	 * @param additionalPrimarySources the additional primary sources to add
	 * @see #SpringApplication(Class...)
	 * @see #getSources()
	 * @see #setSources(Set)
	 * @see #getAllSources()
	 */
	public void addPrimarySources(Collection<Class<?>> additionalPrimarySources) {
		this.primarySources.addAll(additionalPrimarySources);
	}

	/**
	 * Returns a mutable set of the sources that will be added to an ApplicationContext
	 * when {@link #run(String...)} is called.
	 * <p>
	 * Sources set here will be used in addition to any primary sources set in the
	 * constructor.
	 * @return the application sources.
	 * @see #SpringApplication(Class...)
	 * @see #getAllSources()
	 */
	public Set<String> getSources() {
		return this.properties.getSources();
	}

	/**
	 * Set additional sources that will be used to create an ApplicationContext. A source
	 * can be: a class name, package name, or an XML resource location.
	 * <p>
	 * Sources set here will be used in addition to any primary sources set in the
	 * constructor.
	 * @param sources the application sources to set
	 * @see #SpringApplication(Class...)
	 * @see #getAllSources()
	 */
	public void setSources(Set<String> sources) {
		Assert.notNull(sources, "'sources' must not be null");
		this.properties.setSources(sources);
	}

	/**
	 * Return an immutable set of all the sources that will be added to an
	 * ApplicationContext when {@link #run(String...)} is called. This method combines any
	 * primary sources specified in the constructor with any additional ones that have
	 * been {@link #setSources(Set) explicitly set}.
	 * @return an immutable set of all sources
	 */
	public Set<Object> getAllSources() {
		Set<Object> allSources = new LinkedHashSet<>();
		if (!CollectionUtils.isEmpty(this.primarySources)) {
			allSources.addAll(this.primarySources);
		}
		if (!CollectionUtils.isEmpty(this.properties.getSources())) {
			allSources.addAll(this.properties.getSources());
		}
		return Collections.unmodifiableSet(allSources);
	}

	/**
	 * Sets the {@link ResourceLoader} that should be used when loading resources.
	 * @param resourceLoader the resource loader
	 */
	public void setResourceLoader(ResourceLoader resourceLoader) {
		Assert.notNull(resourceLoader, "'resourceLoader' must not be null");
		this.resourceLoader = resourceLoader;
	}

	/**
	 * Return a prefix that should be applied when obtaining configuration properties from
	 * the system environment.
	 * @return the environment property prefix
	 * @since 2.5.0
	 */
	public String getEnvironmentPrefix() {
		return this.environmentPrefix;
	}

	/**
	 * Set the prefix that should be applied when obtaining configuration properties from
	 * the system environment.
	 * @param environmentPrefix the environment property prefix to set
	 * @since 2.5.0
	 */
	public void setEnvironmentPrefix(String environmentPrefix) {
		this.environmentPrefix = environmentPrefix;
	}

	/**
	 * Sets the factory that will be called to create the application context. If not set,
	 * defaults to a factory that will create
	 * {@link AnnotationConfigServletWebServerApplicationContext} for servlet web
	 * applications, {@link AnnotationConfigReactiveWebServerApplicationContext} for
	 * reactive web applications, and {@link AnnotationConfigApplicationContext} for
	 * non-web applications.
	 * @param applicationContextFactory the factory for the context
	 * @since 2.4.0
	 */
	public void setApplicationContextFactory(ApplicationContextFactory applicationContextFactory) {
		this.applicationContextFactory = (applicationContextFactory != null) ? applicationContextFactory
				: ApplicationContextFactory.DEFAULT;
	}

	/**
	 * Sets the {@link ApplicationContextInitializer} that will be applied to the Spring
	 * {@link ApplicationContext}.
	 * @param initializers the initializers to set
	 */
	public void setInitializers(Collection<? extends ApplicationContextInitializer<?>> initializers) {
		this.initializers = new ArrayList<>(initializers);
	}

	/**
	 * Add {@link ApplicationContextInitializer}s to be applied to the Spring
	 * {@link ApplicationContext}.
	 * @param initializers the initializers to add
	 */
	public void addInitializers(ApplicationContextInitializer<?>... initializers) {
		this.initializers.addAll(Arrays.asList(initializers));
	}

	/**
	 * Returns read-only ordered Set of the {@link ApplicationContextInitializer}s that
	 * will be applied to the Spring {@link ApplicationContext}.
	 * @return the initializers
	 */
	public Set<ApplicationContextInitializer<?>> getInitializers() {
		return asUnmodifiableOrderedSet(this.initializers);
	}

	/**
	 * Sets the {@link ApplicationListener}s that will be applied to the SpringApplication
	 * and registered with the {@link ApplicationContext}.
	 * @param listeners the listeners to set
	 */
	public void setListeners(Collection<? extends ApplicationListener<?>> listeners) {
		this.listeners = new ArrayList<>(listeners);
	}

	/**
	 * Add {@link ApplicationListener}s to be applied to the SpringApplication and
	 * registered with the {@link ApplicationContext}.
	 * @param listeners the listeners to add
	 */
	public void addListeners(ApplicationListener<?>... listeners) {
		this.listeners.addAll(Arrays.asList(listeners));
	}

	/**
	 * Returns read-only ordered Set of the {@link ApplicationListener}s that will be
	 * applied to the SpringApplication and registered with the {@link ApplicationContext}
	 * .
	 * @return the listeners
	 */
	public Set<ApplicationListener<?>> getListeners() {
		return asUnmodifiableOrderedSet(this.listeners);
	}

	/**
	 * Set the {@link ApplicationStartup} to use for collecting startup metrics.
	 * @param applicationStartup the application startup to use
	 * @since 2.4.0
	 */
	public void setApplicationStartup(ApplicationStartup applicationStartup) {
		this.applicationStartup = (applicationStartup != null) ? applicationStartup : ApplicationStartup.DEFAULT;
	}

	/**
	 * Returns the {@link ApplicationStartup} used for collecting startup metrics.
	 * @return the application startup
	 * @since 2.4.0
	 */
	public ApplicationStartup getApplicationStartup() {
		return this.applicationStartup;
	}

	/**
	 * Whether to keep the application alive even if there are no more non-daemon threads.
	 * @return whether to keep the application alive even if there are no more non-daemon
	 * threads
	 * @since 3.2.0
	 */
	public boolean isKeepAlive() {
		return this.properties.isKeepAlive();
	}

	/**
	 * Set whether to keep the application alive even if there are no more non-daemon
	 * threads.
	 * @param keepAlive whether to keep the application alive even if there are no more
	 * non-daemon threads
	 * @since 3.2.0
	 */
	public void setKeepAlive(boolean keepAlive) {
		this.properties.setKeepAlive(keepAlive);
	}

	/**
	 * Return a {@link SpringApplicationShutdownHandlers} instance that can be used to add
	 * or remove handlers that perform actions before the JVM is shutdown.
	 * @return a {@link SpringApplicationShutdownHandlers} instance
	 * @since 2.5.1
	 */
	public static SpringApplicationShutdownHandlers getShutdownHandlers() {
		return shutdownHook.getHandlers();
	}

	/**
	 * Static helper that can be used to run a {@link SpringApplication} from the
	 * specified source using default settings.
	 * @param primarySource the primary source to load
	 * @param args the application arguments (usually passed from a Java main method)
	 * @return the running {@link ApplicationContext}
	 */
	public static ConfigurableApplicationContext run(Class<?> primarySource, String... args) {
		return run(new Class<?>[] { primarySource }, args);
	}

	/**
	 * Static helper that can be used to run a {@link SpringApplication} from the
	 * specified sources using default settings and user supplied arguments.
	 * @param primarySources the primary sources to load
	 * @param args the application arguments (usually passed from a Java main method)
	 * @return the running {@link ApplicationContext}
	 */
	public static ConfigurableApplicationContext run(Class<?>[] primarySources, String[] args) {
		return new SpringApplication(primarySources).run(args);
	}

	/**
	 * A basic main that can be used to launch an application. This method is useful when
	 * application sources are defined through a {@literal --spring.main.sources} command
	 * line argument.
	 * <p>
	 * Most developers will want to define their own main method and call the
	 * {@link #run(Class, String...) run} method instead.
	 * @param args command line arguments
	 * @throws Exception if the application cannot be started
	 * @see SpringApplication#run(Class[], String[])
	 * @see SpringApplication#run(Class, String...)
	 */
	public static void main(String[] args) throws Exception {
		SpringApplication.run(new Class<?>[0], args);
	}

	/**
	 * Static helper that can be used to exit a {@link SpringApplication} and obtain a
	 * code indicating success (0) or otherwise. Does not throw exceptions but should
	 * print stack traces of any encountered. Applies the specified
	 * {@link ExitCodeGenerator ExitCodeGenerators} in addition to any Spring beans that
	 * implement {@link ExitCodeGenerator}. When multiple generators are available, the
	 * first non-zero exit code is used. Generators are ordered based on their
	 * {@link Ordered} implementation and {@link Order @Order} annotation.
	 * @param context the context to close if possible
	 * @param exitCodeGenerators exit code generators
	 * @return the outcome (0 if successful)
	 */
	public static int exit(ApplicationContext context, ExitCodeGenerator... exitCodeGenerators) {
		Assert.notNull(context, "'context' must not be null");
		int exitCode = 0;
		try {
			try {
				ExitCodeGenerators generators = new ExitCodeGenerators();
				Collection<ExitCodeGenerator> beans = context.getBeansOfType(ExitCodeGenerator.class).values();
				generators.addAll(exitCodeGenerators);
				generators.addAll(beans);
				exitCode = generators.getExitCode();
				if (exitCode != 0) {
					context.publishEvent(new ExitCodeEvent(context, exitCode));
				}
			}
			finally {
				close(context);
			}
		}
		catch (Exception ex) {
			ex.printStackTrace();
			exitCode = (exitCode != 0) ? exitCode : 1;
		}
		return exitCode;
	}

	/**
	 * Create an application from an existing {@code main} method that can run with
	 * additional {@code @Configuration} or bean classes. This method can be helpful when
	 * writing a test harness that needs to start an application with additional
	 * configuration.
	 * @param main the main method entry point that runs the {@link SpringApplication}
	 * @return a {@link SpringApplication.Augmented} instance that can be used to add
	 * configuration and run the application
	 * @since 3.1.0
	 * @see #withHook(SpringApplicationHook, Runnable)
	 */
	public static SpringApplication.Augmented from(ThrowingConsumer<String[]> main) {
		Assert.notNull(main, "'main' must not be null");
		return new Augmented(main, Collections.emptySet(), Collections.emptySet());
	}

	/**
	 * Perform the given action with the given {@link SpringApplicationHook} attached if
	 * the action triggers an {@link SpringApplication#run(String...) application run}.
	 * @param hook the hook to apply
	 * @param action the action to run
	 * @since 3.0.0
	 * @see #withHook(SpringApplicationHook, ThrowingSupplier)
	 */
	public static void withHook(SpringApplicationHook hook, Runnable action) {
		withHook(hook, () -> {
			action.run();
			return null;
		});
	}

	/**
	 * Perform the given action with the given {@link SpringApplicationHook} attached if
	 * the action triggers an {@link SpringApplication#run(String...) application run}.
	 * @param <T> the result type
	 * @param hook the hook to apply
	 * @param action the action to run
	 * @return the result of the action
	 * @since 3.0.0
	 * @see #withHook(SpringApplicationHook, Runnable)
	 */
	public static <T> T withHook(SpringApplicationHook hook, ThrowingSupplier<T> action) {
		applicationHook.set(hook);
		try {
			return action.get();
		}
		finally {
			applicationHook.remove();
		}
	}

	private static void close(ApplicationContext context) {
		if (context instanceof ConfigurableApplicationContext closable) {
			closable.close();
		}
	}

	private static <E> Set<E> asUnmodifiableOrderedSet(Collection<E> elements) {
		List<E> list = new ArrayList<>(elements);
		list.sort(AnnotationAwareOrderComparator.INSTANCE);
		return new LinkedHashSet<>(list);
	}

	/**
	 * Used to configure and run an augmented {@link SpringApplication} where additional
	 * configuration should be applied.
	 *
	 * @since 3.1.0
	 */
	public static class Augmented {

		private final ThrowingConsumer<String[]> main;

		private final Set<Class<?>> sources;

		private final Set<String> additionalProfiles;

		Augmented(ThrowingConsumer<String[]> main, Set<Class<?>> sources, Set<String> additionalProfiles) {
			this.main = main;
			this.sources = Set.copyOf(sources);
			this.additionalProfiles = additionalProfiles;
		}

		/**
		 * Return a new {@link SpringApplication.Augmented} instance with additional
		 * sources that should be applied when the application runs.
		 * @param sources the sources that should be applied
		 * @return a new {@link SpringApplication.Augmented} instance
		 */
		public Augmented with(Class<?>... sources) {
			LinkedHashSet<Class<?>> merged = new LinkedHashSet<>(this.sources);
			merged.addAll(Arrays.asList(sources));
			return new Augmented(this.main, merged, this.additionalProfiles);
		}

		/**
		 * Return a new {@link SpringApplication.Augmented} instance with additional
		 * profiles that should be applied when the application runs.
		 * @param profiles the profiles that should be applied
		 * @return a new {@link SpringApplication.Augmented} instance
		 * @since 3.4.0
		 */
		public Augmented withAdditionalProfiles(String... profiles) {
			Set<String> merged = new LinkedHashSet<>(this.additionalProfiles);
			merged.addAll(Arrays.asList(profiles));
			return new Augmented(this.main, this.sources, merged);
		}

		/**
		 * Run the application using the given args.
		 * @param args the main method args
		 * @return the running {@link ApplicationContext}
		 */
		public SpringApplication.Running run(String... args) {
			RunListener runListener = new RunListener();
			SpringApplicationHook hook = new SingleUseSpringApplicationHook((springApplication) -> {
				springApplication.addPrimarySources(this.sources);
				springApplication.setAdditionalProfiles(this.additionalProfiles.toArray(String[]::new));
				return runListener;
			});
			withHook(hook, () -> this.main.accept(args));
			return runListener;
		}

		/**
		 * {@link SpringApplicationRunListener} to capture {@link Running} application
		 * details.
		 */
		private static final class RunListener implements SpringApplicationRunListener, Running {

			private final List<ConfigurableApplicationContext> contexts = Collections
				.synchronizedList(new ArrayList<>());

			@Override
			public void contextLoaded(ConfigurableApplicationContext context) {
				this.contexts.add(context);
			}

			@Override
			public ConfigurableApplicationContext getApplicationContext() {
				List<ConfigurableApplicationContext> rootContexts = this.contexts.stream()
					.filter((context) -> context.getParent() == null)
					.toList();
				Assert.state(!rootContexts.isEmpty(), "No root application context located");
				Assert.state(rootContexts.size() == 1, "No unique root application context located");
				return rootContexts.get(0);
			}

		}

	}

	/**
	 * Provides access to details of a {@link SpringApplication} run using
	 * {@link Augmented#run(String...)}.
	 *
	 * @since 3.1.0
	 */
	public interface Running {

		/**
		 * Return the root {@link ConfigurableApplicationContext} of the running
		 * application.
		 * @return the root application context
		 */
		ConfigurableApplicationContext getApplicationContext();

	}

	/**
	 * {@link BeanFactoryPostProcessor} to re-order our property sources below any
	 * {@code @PropertySource} items added by the {@link ConfigurationClassPostProcessor}.
	 */
	private static class PropertySourceOrderingBeanFactoryPostProcessor implements BeanFactoryPostProcessor, Ordered {

		private final ConfigurableApplicationContext context;

		PropertySourceOrderingBeanFactoryPostProcessor(ConfigurableApplicationContext context) {
			this.context = context;
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
			DefaultPropertiesPropertySource.moveToEnd(this.context.getEnvironment());
		}

	}

	/**
	 * Exception that can be thrown to silently exit a running {@link SpringApplication}
	 * without handling run failures.
	 *
	 * @since 3.0.0
	 */
	public static class AbandonedRunException extends RuntimeException {

		private final ConfigurableApplicationContext applicationContext;

		/**
		 * Create a new {@link AbandonedRunException} instance.
		 */
		public AbandonedRunException() {
			this(null);
		}

		/**
		 * Create a new {@link AbandonedRunException} instance with the given application
		 * context.
		 * @param applicationContext the application context that was available when the
		 * run was abandoned
		 */
		public AbandonedRunException(ConfigurableApplicationContext applicationContext) {
			this.applicationContext = applicationContext;
		}

		/**
		 * Return the application context that was available when the run was abandoned or
		 * {@code null} if no context was available.
		 * @return the application context
		 */
		public ConfigurableApplicationContext getApplicationContext() {
			return this.applicationContext;
		}

	}

	/**
	 * {@link SpringApplicationHook} decorator that ensures the hook is only used once.
	 */
	private static final class SingleUseSpringApplicationHook implements SpringApplicationHook {

		private final AtomicBoolean used = new AtomicBoolean();

		private final SpringApplicationHook delegate;

		private SingleUseSpringApplicationHook(SpringApplicationHook delegate) {
			this.delegate = delegate;
		}

		@Override
		public SpringApplicationRunListener getRunListener(SpringApplication springApplication) {
			return this.used.compareAndSet(false, true) ? this.delegate.getRunListener(springApplication) : null;
		}

	}

	/**
	 * Starts a non-daemon thread to keep the JVM alive on {@link ContextRefreshedEvent}.
	 * Stops the thread on {@link ContextClosedEvent}.
	 */
	private static final class KeepAlive implements ApplicationListener<ApplicationContextEvent> {

		private final AtomicReference<Thread> thread = new AtomicReference<>();

		@Override
		public void onApplicationEvent(ApplicationContextEvent event) {
			if (event instanceof ContextRefreshedEvent) {
				startKeepAliveThread();
			}
			else if (event instanceof ContextClosedEvent) {
				stopKeepAliveThread();
			}
		}

		private void startKeepAliveThread() {
			Thread thread = new Thread(() -> {
				while (true) {
					try {
						Thread.sleep(Long.MAX_VALUE);
					}
					catch (InterruptedException ex) {
						break;
					}
				}
			});
			if (this.thread.compareAndSet(null, thread)) {
				thread.setDaemon(false);
				thread.setName("keep-alive");
				thread.start();
			}
		}

		private void stopKeepAliveThread() {
			Thread thread = this.thread.getAndSet(null);
			if (thread == null) {
				return;
			}
			thread.interrupt();
		}

	}

	/**
	 * Strategy used to handle startup concerns.
	 */
	abstract static class Startup {

		private Duration timeTakenToStarted;

		protected abstract long startTime();

		protected abstract Long processUptime();

		protected abstract String action();

		final Duration started() {
			long now = System.currentTimeMillis();
			this.timeTakenToStarted = Duration.ofMillis(now - startTime());
			return this.timeTakenToStarted;
		}

		Duration timeTakenToStarted() {
			return this.timeTakenToStarted;
		}

		private Duration ready() {
			long now = System.currentTimeMillis();
			return Duration.ofMillis(now - startTime());
		}

		static Startup create() {
			ClassLoader classLoader = Startup.class.getClassLoader();
			return (ClassUtils.isPresent("jdk.crac.management.CRaCMXBean", classLoader)
					&& ClassUtils.isPresent("org.crac.management.CRaCMXBean", classLoader))
							? new CoordinatedRestoreAtCheckpointStartup() : new StandardStartup();
		}

	}

	/**
	 * Standard {@link Startup} implementation.
	 */
	private static final class StandardStartup extends Startup {

		private final Long startTime = System.currentTimeMillis();

		@Override
		protected long startTime() {
			return this.startTime;
		}

		@Override
		protected Long processUptime() {
			try {
				return ManagementFactory.getRuntimeMXBean().getUptime();
			}
			catch (Throwable ex) {
				return null;
			}
		}

		@Override
		protected String action() {
			return "Started";
		}

	}

	/**
	 * Coordinated-Restore-At-Checkpoint {@link Startup} implementation.
	 */
	private static final class CoordinatedRestoreAtCheckpointStartup extends Startup {

		private final StandardStartup fallback = new StandardStartup();

		@Override
		protected Long processUptime() {
			long uptime = CRaCMXBean.getCRaCMXBean().getUptimeSinceRestore();
			return (uptime >= 0) ? uptime : this.fallback.processUptime();
		}

		@Override
		protected String action() {
			return (restoreTime() >= 0) ? "Restored" : this.fallback.action();
		}

		private long restoreTime() {
			return CRaCMXBean.getCRaCMXBean().getRestoreTime();
		}

		@Override
		protected long startTime() {
			long restoreTime = restoreTime();
			return (restoreTime >= 0) ? restoreTime : this.fallback.startTime();
		}

	}

	/**
	 * {@link OrderSourceProvider} used to obtain factory method and target type order
	 * sources. Based on internal {@link DefaultListableBeanFactory} code.
	 */
	private class FactoryAwareOrderSourceProvider implements OrderSourceProvider {

		private final ConfigurableBeanFactory beanFactory;

		private final Map<?, String> instancesToBeanNames;

		FactoryAwareOrderSourceProvider(ConfigurableBeanFactory beanFactory, Map<?, String> instancesToBeanNames) {
			this.beanFactory = beanFactory;
			this.instancesToBeanNames = instancesToBeanNames;
		}

		@Override
		public Object getOrderSource(Object obj) {
			String beanName = this.instancesToBeanNames.get(obj);
			return (beanName != null) ? getOrderSource(beanName, obj.getClass()) : null;
		}

		private Object getOrderSource(String beanName, Class<?> instanceType) {
			try {
				RootBeanDefinition beanDefinition = (RootBeanDefinition) this.beanFactory
					.getMergedBeanDefinition(beanName);
				Method factoryMethod = beanDefinition.getResolvedFactoryMethod();
				Class<?> targetType = beanDefinition.getTargetType();
				targetType = (targetType != instanceType) ? targetType : null;
				return Stream.of(factoryMethod, targetType).filter(Objects::nonNull).toArray();
			}
			catch (NoSuchBeanDefinitionException ex) {
				return null;
			}
		}

	}

}
