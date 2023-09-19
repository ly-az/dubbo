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

package com.alibaba.dubbo.common.threadpool.support.eager;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.threadlocal.NamedInternalThreadFactory;
import com.alibaba.dubbo.common.threadpool.ThreadPool;
import com.alibaba.dubbo.common.threadpool.support.AbortPolicyWithReport;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * EagerThreadPool
 * When the core threads are all in busy,
 * create new thread instead of putting task into blocking queue.
 * <p>
 * JDK 线程池的默认策略是，越小的线程开销越好，所以会优先使用队列，而不是创建新的线程。
 * <p>
 * 在 RPC 通信框架的场景下，JDK 线程池这样的策略是不满足的
 * 示例：
 * 假设RPC的业务处理线程池里corePoolSize是10，maxPoolSize是40，任务队列长度是100
 * 那么此时如果突然有30个RPC请求过来，而且这30个RPC业务比较耗时，此时只有10个RPC请求在响应并执行，剩下的20个RPC请求还在任务队列里
 * 因为任务队列是100，还没有满，所以不会创建出额外的线程来处理。
 * 需要积压了100个RPC请求，才会开始创建新的线程，来处理这些RPC业务，这是不可接受的。
 * <p>
 * 那有没有一种线程池，可以实现如下功能，在corePoolSize是10，maxPoolSize是40，任务队列长度是100时：
 * 1. 如果有20个请求过来，corePoolSize的10个线程不够的时候，立刻再创建出10个线程来，立刻处理这20个请求。
 * 2. 当同时有50个请求过来，创建的线程已经超过maxPoolSize的40的时候，再把处理不了的10个放在任务队列里。
 * 3. 当请求变少的时候，maxPoolSize创建出来的那30个额外线程再释放掉，释放资源。
 * <p>
 *     EagerThreadPool 结合自己实现的 TaskQueue 和 EagerThreadPoolExecutor 就实现了上述的功能
 *
 *
 */
public class EagerThreadPool implements ThreadPool {

    @Override
    public Executor getExecutor(URL url) {
        String name = url.getParameter(Constants.THREAD_NAME_KEY, Constants.DEFAULT_THREAD_NAME);
        int cores = url.getParameter(Constants.CORE_THREADS_KEY, Constants.DEFAULT_CORE_THREADS);
        int threads = url.getParameter(Constants.THREADS_KEY, Integer.MAX_VALUE);
        int queues = url.getParameter(Constants.QUEUES_KEY, Constants.DEFAULT_QUEUES);
        int alive = url.getParameter(Constants.ALIVE_KEY, Constants.DEFAULT_ALIVE);

        // init queue and executor
        TaskQueue<Runnable> taskQueue = new TaskQueue<Runnable>(queues <= 0 ? 1 : queues);
        EagerThreadPoolExecutor executor = new EagerThreadPoolExecutor(cores,
                threads,
                alive,
                TimeUnit.MILLISECONDS,
                taskQueue,
                new NamedInternalThreadFactory(name, true),
                new AbortPolicyWithReport(name, url));
        taskQueue.setExecutor(executor);
        return executor;
    }
}
