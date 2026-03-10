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

package org.springframework.boot.context.annotation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

import org.springframework.core.io.UrlResource;
import org.springframework.util.Assert;

/**
 * Contains {@code @Configuration} import candidates, usually auto-configurations.
 *
 * The {@link #load(Class, ClassLoader)} method can be used to discover the import
 * candidates.
 *
 * @author Moritz Halbritter
 * @author Scott Frederick
 * @since 2.7.0
 */
// ImportCandidates 的核心作用是：从类路径（Classpath）中加载并解析特定的 .imports 文件，以获取待导入的配置类列表。
// 在 Spring Boot 2.7 之前，所有的自动配置类都写在 META-INF/spring.factories 里的 EnableAutoConfiguration 键下。随着自动配置类越来越多，这个文件变得臃肿且难以维护。
//ImportCandidates 引入了新的规范：
//
//新位置：META-INF/spring/全限定注解名.imports。
//
//按注解解耦：例如 @EnableAutoConfiguration 的候选类现在存储在 META-INF/spring/org.springframework.boot.autoconfigure.EnableAutoConfiguration.imports 文件中。
public final class ImportCandidates implements Iterable<String> {
	// 定义了 .imports 文件的存储路径模板：META-INF/spring/%s.imports。其中 %s 会被替换为注解的全限定名。
	private static final String LOCATION = "META-INF/spring/%s.imports";

	private static final String COMMENT_START = "#";
	// 存储从文件中加载出来的所有待导入类的全限定名列表。该列表是不可变的（Unmodifiable）
	private final List<String> candidates;

	private ImportCandidates(List<String> candidates) {
		Assert.notNull(candidates, "'candidates' must not be null");
		this.candidates = Collections.unmodifiableList(candidates);
	}

	@Override
	public Iterator<String> iterator() {
		return this.candidates.iterator();
	}

	/**
	 * Returns the list of loaded import candidates.
	 * @return the list of import candidates
	 */
	public List<String> getCandidates() {
		return this.candidates;
	}

	/**
	 * Loads the names of import candidates from the classpath. The names of the import
	 * candidates are stored in files named
	 * {@code META-INF/spring/full-qualified-annotation-name.imports} on the classpath.
	 * Every line contains the full qualified name of the candidate class. Comments are
	 * supported using the # character.
	 * @param annotation annotation to load
	 * @param classLoader class loader to use for loading
	 * @return list of names of annotated classes
	 */
	// 类似于 Java 原生的 ServiceLoader，但它是为 Spring Boot 的注解驱动模型定制的。它的目标是：在不扫描整个类路径的情况下，精准地找到所有声明支持某个注解的配置类。
	public static ImportCandidates load(Class<?> annotation, ClassLoader classLoader) {
		Assert.notNull(annotation, "'annotation' must not be null");
		// 确保有一个可用的类加载器。
		ClassLoader classLoaderToUse = decideClassloader(classLoader);
		// 将 LOCATION 常量（META-INF/spring/%s.imports）中的占位符替换为传入注解的全限定名。
		String location = String.format(LOCATION, annotation.getName());
		// 跨 Jar 包资源检索
		// 这是关键的一步。它不会只找一个文件，而是会搜索类路径下所有 Jar 包中符合该路径的文件。这使得每个第三方 Starter 都可以拥有自己的 .imports 文件。
		Enumeration<URL> urls = findUrlsInClasspath(classLoaderToUse, location);
		List<String> importCandidates = new ArrayList<>();
		// 遍历每一个找到的 URL，调用 readCandidateConfigurations 读取文件内容。
		while (urls.hasMoreElements()) {
			URL url = urls.nextElement();
			importCandidates.addAll(readCandidateConfigurations(url));
		}
		return new ImportCandidates(importCandidates);
	}

	private static ClassLoader decideClassloader(ClassLoader classLoader) {
		if (classLoader == null) {
			return ImportCandidates.class.getClassLoader();
		}
		return classLoader;
	}

	private static Enumeration<URL> findUrlsInClasspath(ClassLoader classLoader, String location) {
		try {
			return classLoader.getResources(location);
		}
		catch (IOException ex) {
			throw new IllegalArgumentException("Failed to load configurations from location [" + location + "]", ex);
		}
	}

	private static List<String> readCandidateConfigurations(URL url) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new UrlResource(url).getInputStream(), StandardCharsets.UTF_8))) {
			List<String> candidates = new ArrayList<>();
			String line;
			while ((line = reader.readLine()) != null) {
				line = stripComment(line);
				line = line.trim();
				if (line.isEmpty()) {
					continue;
				}
				candidates.add(line);
			}
			return candidates;
		}
		catch (IOException ex) {
			throw new IllegalArgumentException("Unable to load configurations from location [" + url + "]", ex);
		}
	}

	private static String stripComment(String line) {
		int commentStart = line.indexOf(COMMENT_START);
		if (commentStart == -1) {
			return line;
		}
		return line.substring(0, commentStart);
	}

}
