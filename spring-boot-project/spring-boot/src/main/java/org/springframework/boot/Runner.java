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

/**
 * Marker interface for runners.
 *
 * @author Tadaya Tsuyukubo
 * @see ApplicationRunner
 * @see CommandLineRunner
 */
// 在 Spring Boot 的设计架构中，它的存在主要是为了统一管理不同类型的运行器。
// 插件化扩展：通过这个标记接口，Spring Boot 的启动流程逻辑（即 callRunners 方法）可以保持通用性。如果未来 Spring Boot 引入了第三种运行器（例如 ReactiveRunner），只需要让它继承 Runner 接口，原有的启动逻辑无需修改即可兼容。
interface Runner {

}
