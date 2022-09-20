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
package com.alibaba.dubbo.rpc.protocol.injvm;

import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.protocol.AbstractExporter;

import java.util.Map;

/**
 * InjvmExporter
 * 本地暴露的 Exporter 实现
 */
class InjvmExporter<T> extends AbstractExporter<T> {

    /**
     * 服务名
     */
    private final String key;

    /**
     *
     * injvm 协议下的 Exporter 对象的 map 集合
     * key 是服务名
     */
    private final Map<String, Exporter<?>> exporterMap;

    /**
     * 这个构造方法就是服务本地暴露的发起
     * @param invoker invoker 对象
     * @param key 服务名
     * @param exporterMap exporter 集合
     */
    InjvmExporter(Invoker<T> invoker, String key, Map<String, Exporter<?>> exporterMap) {
        super(invoker);
        this.key = key;
        this.exporterMap = exporterMap;
        exporterMap.put(key, this);
    }

    /**
     * 取消暴露
     */
    @Override
    public void unexport() {
        super.unexport();
        // 从 exporter 对象集合中移除
        exporterMap.remove(key);
    }

}
