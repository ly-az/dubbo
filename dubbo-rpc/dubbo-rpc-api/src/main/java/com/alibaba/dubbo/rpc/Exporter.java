/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc;

/**
 * Exporter. (API/SPI, Prototype, ThreadSafe)
 *
 * @see com.alibaba.dubbo.rpc.Protocol#export(Invoker)
 * @see com.alibaba.dubbo.rpc.ExporterListener
 * @see com.alibaba.dubbo.rpc.protocol.AbstractExporter
 *
 * Dubbo 处理服务暴露的关键就在于 Invoker 转换到 Exporter 的过程
 *  Dubbo 的实现，dubbo 协议的 Invoker 转为 Exporter 发生在 DubboProtocol 类的 export() 方法
 *  主要是打开 socket 侦听服务，并接收客户端发来的各种请求，通讯细节由 Dubbo 实现
 * <p>
 *  RMI 的实现，RMI 协议的 Invoker 转为 Exporter 发生在 RmiProtocol 类的 export() 方法
 *  通过 Spring 或者 Dubbo 或者 JDK 来实现 RMI 服务，通讯细节这部分由 JDK 底层来实现
 *
 *  Exporter，Invoker 暴露服务在 Protocol 上的对象
 *
 *
 */
public interface Exporter<T> {

    /**
     * get invoker.
     *
     * @return invoker
     *
     * 获取对应的 Invoker 对象
     *
     */
    Invoker<T> getInvoker();

    /**
     * unexport.
     * <p>
     * <code>
     * getInvoker().destroy();
     * </code>
     *
     * 取消暴露，通过实现该方法，使相同的 Invoker 在不同的 Protocol 实现取消暴露逻辑
     */
    void unexport();

}