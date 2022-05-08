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

import com.alibaba.dubbo.common.Node;

/**
 * Invoker. (API/SPI, Prototype, ThreadSafe)
 *
 * @see com.alibaba.dubbo.rpc.Protocol#refer(Class, com.alibaba.dubbo.common.URL)
 * @see com.alibaba.dubbo.rpc.InvokerListener
 * @see com.alibaba.dubbo.rpc.protocol.AbstractInvoker
 *
 * Invoker 是实体域，是 Dubbo 模型的核心，其他模型都向其靠拢，或者转化为它
 * Invoker 代表的是一个可执行体，可以向其发起 invoke 调用
 * Invoker 可能是本地实现，也可能是远程实现，甚至可能是一个集群实现
 *
 * Invoker 可以区分为 服务 Invoker 和消费 Invoker，两者通过 TCP/IP 协议进行通信
 *
 */
public interface Invoker<T> extends Node {

    /**
     * get service interface.
     *
     * @return service interface.
     *
     * 获取 service 接口
     *
     */
    Class<T> getInterface();

    /**
     * invoke.
     *
     * @param invocation
     * @return result
     * @throws RpcException
     *
     * 调用方法
     *
     */
    Result invoke(Invocation invocation) throws RpcException;

}