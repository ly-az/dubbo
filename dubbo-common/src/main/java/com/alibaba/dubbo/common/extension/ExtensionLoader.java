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
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.support.ActivateComparator;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.Holder;
import com.alibaba.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see com.alibaba.dubbo.common.extension.SPI
 * @see com.alibaba.dubbo.common.extension.Adaptive
 * @see com.alibaba.dubbo.common.extension.Activate
 * <p>
 * SPI 扩展的加载器，这是 Dubbo SPI 机制的核心类
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    /**
     * Java SPI 的配置目录
     * Dubbo SPI 对于 Java SPI实现了兼容和增强
     */
    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    /**
     * 用户自定义的拓展实现的路径
     */
    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    /**
     * 拓展名分隔符，
     */
    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    /*
    下面的可以划分为两种属性：
    【静态属性】一方面，ExtensionLoader 是 ExtensionLoader 的管理容器
        一个拓展( 拓展接口 )对应一个 ExtensionLoader 对象，例如，Protocol 和 Filter 分别对应一个 ExtensionLoader 对象
    【对象属性】另一方面，一个拓展通过其 ExtensionLoader 对象，加载它的拓展实现们
        可以发现会发现多个属性都是 “cached“ 开头。ExtensionLoader 考虑到性能和资源的优化，读取拓展配置后，会首先进行缓存
        等到 Dubbo 代码真正用到对应的拓展实现时，进行拓展实现的对象的初始化
        并且，初始化完成后，也会进行缓存。也就是说：
            缓存加载的拓展配置
            缓存创建的拓展实现对象
     */

    /**
     * 扩展加载器的集合
     * key 拓展接口
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

    /**
     * 拓展实现集合
     * key 拓展实现类
     * value 拓展实现类的对象
     * 例如：key -> Class<AccessLogFilter>, value -> AccessLogFilter 对象
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    // ==============================

    /**
     * 拓展接口
     * 例如 Protocol
     */
    private final Class<?> type;

    /**
     * 对象工厂，其具体功能是 Spring IoC 一致
     * 用于调用 {@link #injectExtension(Object)} 方法，向拓展对象注入依赖属性
     * 例如，StubProxyFactoryWrapper 中有 `Protocol protocol` 属性
     */
    private final ExtensionFactory objectFactory;

    /**
     * 缓存的拓展类与拓展名
     * {@see #cachedClasses}的 K-V 值对调
     * 通过 {@link #loadExtensionClasses} 加载
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();

    /**
     * 缓存的拓展名与拓展类，但是不包含以下两种类型：
     * 1. 自适应的拓展实现，例如 AdaptiveExtensionFactory
     * 2. 带唯一参数为拓展接口的构造方法实现类，或者说是拓展 Wrapper 实现类，例如：ProtocolFilterWrapper
     * 拓展 Wrapper 实现类，会添加到 {@link #cachedWrapperClasses} 中
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();

    /**
     * 拓展名与 @Activate 注解的实现集合，例如 AccessLogFilter
     * 用于 {@link #getActivateExtension(URL, String)} 方法
     */
    private final Map<String, Activate> cachedActivates = new ConcurrentHashMap<String, Activate>();
    /**
     * 缓存的拓展对象集合
     * key-拓展名，value-拓展对象
     * 例如： Protocol 拓展
     * key：dubbo value：DubboProtocol
     * key：injvm value：InjvmProtocol
     * 通过 {@link #loadExtensionClasses} 方法进行加载
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();
    /**
     * 缓存的自适应( Adaptive )拓展对象
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();
    /**
     * 缓存的自适应拓展对象的类
     * {@link #getAdaptiveExtensionClass()}
     */
    private volatile Class<?> cachedAdaptiveClass = null;
    /**
     * 缓存的默认拓展名
     * 通过 {@link SPI} 注解获取
     */
    private String cachedDefaultName;
    /**
     * 创建 {@link #cachedAdaptiveInstance} 时发生的异常
     * 发生异常后，不再创建，参见 {@link #createAdaptiveExtension()}
     */
    private volatile Throwable createAdaptiveInstanceError;

    /**
     * 拓展 Wrapper 实现类集合
     * 带有唯一参数的拓展接口的构造方法的实现类
     * 通过 {@link #loadExtensionClasses()} 方法进行加载
     */
    private Set<Class<?>> cachedWrapperClasses;

    /**
     * 拓展名与加载对应的拓展类发生的异常
     * key-拓展名，value-异常
     * 在执行{@link #loadDirectory(Map, String)} 方法时出现异常的情况下记录
     */
    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    /**
     * @param type 拓展接口
     *             <p>
     *             这里判断了传入的拓展接口是否是 ExtensionFactory，这里的判断避免了死循环
     *             如果当前的拓展类接口不是 ExtensionFactory ，获得 ExtensionFactory 拓展接口的自适应拓展实现对象
     *             这里为什么会产生死循环？？
     *             可以看看拓展工厂的具体实现：
     * @see com.alibaba.dubbo.common.extension.ExtensionFactory
     */
    private ExtensionLoader(Class<?> type) {
        this.type = type;
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    /**
     * 是否有 SPI 注解
     *
     * @param type 扩展类型
     * @param <T>
     * @return
     */
    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    /**
     * 获取扩展加载器
     *
     * @param type 扩展类型
     * @param <T>  泛型
     * @return 对应的加载器
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        // 非空校验
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        // 是否为接口
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        // 是否带有 SPI 注解
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }

        // 从缓存中获取拓展对应的加载器
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        // 如果缓存中没有，则创建，并加入缓存中
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    private static ClassLoader findClassLoader() {
        return ExtensionLoader.class.getClassLoader();
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        // 从 URL 中获取 key 对应的 value
        String value = url.getParameter(key);
        // 获取符合自动激活条件的拓展集合
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see com.alibaba.dubbo.common.extension.Activate
     * <p>
     * 获取符合条件的自动激活的拓展集合
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<T>();
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
        // 处理自动激活的拓展对象
        // 判断不存在的 “-name”， 例如 <dubbo:service filter="-default" /> ，代表移除所有默认过滤器
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
            getExtensionClasses(); // 获取拓展实现类
            for (Map.Entry<String, Activate> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();
                Activate activate = entry.getValue();
                // 获取符合条件的拓展实现
                if (isMatchGroup(group, activate.group())) {
                    if (!names.contains(name) //  不包含在自定义配置里。如果包含，会在下面的代码处理
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name) // 判断是否配置移除。例如 <dubbo:service filter="-monitor" />，则 MonitorFilter 会被移除
                            && isActive(activate, url)) { // 判断是否激活
                        T ext = getExtension(name); // 获取拓展类实例
                        exts.add(ext);
                    }
                }
            }
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }
        // 处理自定义配置的拓展。例如 <dubbo:service filter="demo" /> ，代表需要加入 DemoFilter
        List<T> usrs = new ArrayList<T>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
                // 将配置的自定义拓展类添加到自动激活的拓展对象集合的前面
                // 例如，<dubbo:service filter="demo,default,demo2" /> ，则 DemoFilter 就会放在默认的过滤器前面。
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    // 获取拓展类实例
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (!usrs.isEmpty()) {
            // 添加到结果集
            exts.addAll(usrs);
        }
        return exts;
    }


    /**
     * 匹配分组
     *
     * @param group  过滤的分组条件。若为空，无需过滤
     * @param groups 配置的分组
     * @return 是否匹配
     */
    private boolean isMatchGroup(String group, String[] groups) {
        // 若 group 为空，不用匹配
        if (group == null || group.length() == 0) {
            return true;
        }
        // 遍历分组数组，判断是否匹配
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 是否激活，通过 Dubbo URL 中是否存在参数名为 `@Activate.value` ，并且参数值非空。
     *
     * @param activate 自动激活注解
     * @param url      URL 对象
     * @return 是否激活
     */
    private boolean isActive(Activate activate, URL url) {
        String[] keys = activate.value();
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     * <p>
     * 返回扩展点实例，如果没有指定的扩展点或是还没加载（即实例化），则返回<code>null</code>。注意：此方法不会触发扩展点的加载。
     * <p/>
     * 一般应该调用{@link #getExtension(String)}方法获得扩展，这个方法会触发扩展点加载。
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     * <p>
     * 返回已经加载的扩展点的名字。
     * <p/>
     * 一般应该调用{@link #getSupportedExtensions()}方法获得扩展，这个方法会返回所有的扩展点。
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     * <p>
     * 寻找指定名称的拓展并返回实现对象，如果不存在，则抛出 IllegalStateException 异常
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        // 查找默认的拓展对象
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        // 从缓存中查找拓展对象
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                // 如果缓存中没有对应的拓展对象，就创建并加入到缓存中
                if (instance == null) {
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     * 返回缺省的扩展，如果没有设置则返回<code>null</code>。
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0
                || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        try {
            this.getExtensionClass(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     * 返回缺省的扩展点名，如果没有设置缺省则返回<code>null</code>。
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     *                               <p>
     *                               通过 API 注册扩展实现类（手动编程的方式）
     *                               例如，CacheFactory 有两个实现类，分别是 JCacheFactory 和 AdaptiveCacheFactory
     *                               通过 {@link #addExtension(String, Class)} 方法，可以手动将 JCacheFactory 注册到 Dubbo 中
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     * 通过 API 替换扩展实现类（手动编程的方式）
     * 例如，CacheFactory 有两个实现类，分别是 JCacheFactory 和 AdaptiveCacheFactory
     * 通过 {@link #replaceExtension(String, Class)} 方法，可以手动将 AdaptiveCacheFactory 替换掉 JCacheFactory
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * 获取自适应的拓展类对象
     *
     * @return 自适应的拓展类对象
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        // 从缓存中获取
        Object instance = cachedAdaptiveInstance.get();
        // 缓存中没有
        if (instance == null) {
            // 如果之前没有创建失败的报错
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            // 创建自适应的拓展对象并加入到缓存中
                            instance = createAdaptiveExtension();
                            // 设置到缓存中
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            // 创建失败，记录异常
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                // 如果之前的创建失败了，就直接抛出异常
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }


    /**
     * 获取扩展名不存在时的异常
     *
     * @param name 扩展名
     * @return 异常
     */
    private IllegalStateException findException(String name) {
        // 在 `#loadFile(...)` 方法中，加载时，发生异常
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }

        // 生成不存在该拓展类实现的异常。
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     * 创建指定拓展名称的拓展对象
     *
     * @param name 拓展名称
     * @return 拓展对象
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        // 获取拓展名对应的拓展实现类
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            // 抛出异常
            throw findException(name);
        }
        try {
            // 从缓存中，根据拓展实现类获取拓展对象
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                // 如果缓存中不存在，就创建并加入缓存
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            // 为创建的拓展类注入属性
            injectExtension(instance);
            // 创建 Wrapper 拓展对象
            /*
            Wrapper 类也实现了拓展类接口
            但是 Wrapper 不是拓展类的真正实现
            作用：从 ExtensionLoader 返回拓展类时，包装在真正的拓展类实现
                -- 也就是从 ExtensionLoader 返回的实际上是 Wrapper 类的实例，只是 该实例持有真正的拓展类实现
            拓展类的 Wrapper 可以有多个，也可以新增

            通过 Wrapper 类可以把所有的扩展类的公共逻辑都在 Wrapper 中处理
            新增的 Wrapper在所有扩展类的上添加了逻辑，就类似 AOP 的处理，Wrapper 类代理了 扩展类
             */
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (wrapperClasses != null && !wrapperClasses.isEmpty()) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * 向拓展实现类的实体中注入各种属性
     *
     * @param instance 拓展实现类实体
     * @return
     */
    private T injectExtension(T instance) {
        try {
            // 必须持有 objectFactor ，也就是说必须是 ExtensionFactory 的拓展对象
            if (objectFactory != null) {
                for (Method method : instance.getClass().getMethods()) {
                    if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) { // 获取公共的 setter 方法
                        /**
                         * Check {@link DisableInject} to see if we need auto injection for this property
                         * 校验该拓展类实体是否需要注入属性
                         */
                        if (method.getAnnotation(DisableInject.class) != null) {
                            continue;
                        }
                        // 获取属性类型
                        Class<?> pt = method.getParameterTypes()[0];
                        try {
                            // 获取属性
                            String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
                            // 获取属性值并设置该值
                            // 实际上，这一步获取的不仅仅是 拓展类，也可以是 Spring Bean
                            Object object = objectFactory.getExtension(pt, property);
                            if (object != null) {
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("fail to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (name == null)
            throw new IllegalArgumentException("Extension name == null");
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null)
            throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
        return clazz;
    }

    /**
     * 获取拓展的实现类的集合
     *
     * @return 拓展的实现类的集合
     */
    private Map<String, Class<?>> getExtensionClasses() {
        // 直接从缓存中获取
        Map<String, Class<?>> classes = cachedClasses.get();
        // 如果缓存中没有
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    // 从配置文件中获取,并设置到缓存中
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * 获取拓展实现类集合
     *
     * @return 拓展实现
     * <p>
     * 无需声明 synchronized ，因为唯一调用该方法的 {@link #getExtensionClasses()} 已经声明
     */
    // synchronized in getExtensionClasses
    private Map<String, Class<?>> loadExtensionClasses() {
        // 通过 SPI 注解，获取拓展实现类名
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                if (names.length == 1) cachedDefaultName = names[0];
            }
        }

        // 从配置文件中，获取拓展实现类，顺序是：dubbo 内部拓展实现 -> 用户自定义的拓展实现 -> Java SPI的拓展实现
        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
        loadDirectory(extensionClasses, DUBBO_DIRECTORY);
        loadDirectory(extensionClasses, SERVICES_DIRECTORY);
        return extensionClasses;
    }

    /**
     * 从配置目录中获取真正的拓展实现
     *
     * @param extensionClasses 拓展实现类
     * @param dir              配置目录
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir) {
        // 完整的文件名
        String fileName = dir + type.getName();
        try {
            Enumeration<java.net.URL> urls;
            // 获取类加载器，并通过完整的文件名获取对应的文件 URL
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    // 通过拓展实现类，类加载器，拓展URL 获取拓展实现
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    /**
     * 获取拓展实现
     *
     * @param extensionClasses 拓展实现类
     * @param classLoader      类加载器
     * @param resourceURL      拓展 URL
     */
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            // 获取文件流
            BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), "utf-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 过滤注释
                    final int ci = line.indexOf('#');
                    if (ci >= 0) line = line.substring(0, ci);
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            // 对 Key=Value 进行拆分
                            int i = line.indexOf('=');
                            // 按照下面的 拓展名的解析方式，Java SPI 的拓展名是为空的，所以为了兼容后面实现了自动生成拓展名
                            if (i > 0) {
                                name = line.substring(0, i).trim();
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    /**
     * 拓展类的加载
     *
     * @param extensionClasses 拓展类
     * @param resourceURL      拓展配置的 URL
     * @param clazz            拓展类的 class 对象
     * @param name             拓展类名
     * @throws NoSuchMethodException 异常
     */
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        // 判断拓展实现，是否实现拓展接口
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error when load extension class(interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + "is not subtype of interface.");
        }
        // 缓存自适应拓展对象的类到 `cachedAdaptiveClass`
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            if (cachedAdaptiveClass == null) {
                cachedAdaptiveClass = clazz;
            } else if (!cachedAdaptiveClass.equals(clazz)) {
                throw new IllegalStateException("More than 1 adaptive class found: "
                        + cachedAdaptiveClass.getClass().getName()
                        + ", " + clazz.getClass().getName());
            }
        } else if (isWrapperClass(clazz)) {
            // 缓存拓展 Wrapper 实现类到 `cachedWrapperClasses`
            Set<Class<?>> wrappers = cachedWrapperClasses;
            if (wrappers == null) {
                cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                wrappers = cachedWrapperClasses;
            }
            wrappers.add(clazz);
        } else {
            // 缓存拓展实现类到 `extensionClasses`
            clazz.getConstructor();
            if (name == null || name.length() == 0) {
                // 获取拓展名，如未配置拓展名，进行自动生成，这是对于 Java SPI 的兼容
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }
            // 获得拓展名，可以是数组，有多个拓展名，使用 逗号 分隔
            String[] names = NAME_SEPARATOR.split(name);
            if (names != null && names.length > 0) {
                // 缓存 @Activate 到 `cachedActivates`
                Activate activate = clazz.getAnnotation(Activate.class);
                if (activate != null) {
                    cachedActivates.put(names[0], activate);
                }
                for (String n : names) {
                    // 缓存到 `cachedNames`
                    if (!cachedNames.containsKey(clazz)) {
                        cachedNames.put(clazz, n);
                    }
                    // 缓存拓展实现类到 `extensionClasses`
                    // 相同的拓展名，不能对应多个不同的拓展实现
                    Class<?> c = extensionClasses.get(n);
                    if (c == null) {
                        // 发生异常，记录到异常集合
                        extensionClasses.put(n, clazz);
                    } else if (c != clazz) {
                        throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                    }
                }
            }
        }
    }

    /**
     * 判断是否是 Wrapper 扩展
     *
     * @param clazz 拓展类的 class 对象
     * @return 是否为 wrapper 拓展
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            // 可以成功获取到构造方法
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            // 若获得构造方法失败，则代表是普通的拓展实现类
            return false;
        }
    }

    /**
     * 获取拓展名
     *
     * @param clazz 拓展类的 class 对象
     * @return
     */
    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        com.alibaba.dubbo.common.Extension extension = clazz.getAnnotation(com.alibaba.dubbo.common.Extension.class);
        // 未配置拓展名，自动生成。例如，DemoFilter 为 demo 。主要用于兼容 Java SPI 的配置
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    /**
     * 创建自适应的拓展类实例
     *
     * @return 自适应的拓展类实例
     */
    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 获取自适应拓展类
     *
     * @return 自适应拓展类
     */
    private Class<?> getAdaptiveExtensionClass() {
        // 直接从缓存中获取
        getExtensionClasses();
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        // 创建并加入缓存
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 自动生成自适应拓展的代码实现，并编译后返回该类。
     *
     * @return 自适应拓展的代码实现
     */
    private Class<?> createAdaptiveExtensionClass() {
        // 自动生成自适应拓展的代码实现的字符串
        String code = createAdaptiveExtensionClassCode();
        // 编译代码，并返回该类
        ClassLoader classLoader = findClassLoader();
        com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader);
    }

    /**
     * 自动生成自适应拓展的代码实现的字符串
     *
     * @return 自适应拓展的代码实现的字符串
     */
    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuilder = new StringBuilder();
        // 遍历方法，判断是否有 `@Adaptive· 注解
        Method[] methods = type.getMethods();
        boolean hasAdaptiveAnnotation = false;
        for (Method m : methods) {
            if (m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }

        // 没有找到 `@Adaptive` 注解，说明不需要生成 Adaptive 类
        // no need to generate adaptive class since there's no adaptive method found.
        if (!hasAdaptiveAnnotation)
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");
        // 生成 package 代码：package + type 所在包
        codeBuilder.append("package ").append(type.getPackage().getName()).append(";");
        // 生成 import 代码：import + ExtensionLoader 全限定名
        codeBuilder.append("\nimport ").append(ExtensionLoader.class.getName()).append(";");
        // 生成类代码：public class + type简单名称 + $Adaptive + implements + type全限定名 + {
        codeBuilder.append("\npublic class ").append(type.getSimpleName()).append("$Adaptive").append(" implements ").append(type.getCanonicalName()).append(" {");

        // 循环方法数组
        for (Method method : methods) {
            Class<?> rt = method.getReturnType(); // 返回类型
            Class<?>[] pts = method.getParameterTypes(); // 参数类型数组
            Class<?>[] ets = method.getExceptionTypes(); // 异常类型数组

            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
            // 方法体
            StringBuilder code = new StringBuilder(512);
            // 如果方法上没有 Adaptive 注解，则直接 throw new UnsupportedOperationException(...)
            // 非自适应的方法是不应该被调用的
            if (adaptiveAnnotation == null) {
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
            } else {
                // 有 Adaptive 注解，则生成自适应拓展的代码实现
                // 寻找 Dubbo URL 参数的位置
                int urlTypeIndex = -1;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }
                // found parameter in URL type
                // urlTypeIndex != -1，表示参数列表中存在 URL 参数
                if (urlTypeIndex != -1) {
                    // Null Point check
                    // 有类型为URL的参数，生成代码：生成校验 URL 非空的代码
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                            urlTypeIndex);
                    code.append(s);

                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                    code.append(s);
                }
                // did not find parameter in URL type
                else {
                    // 参数列表中不存在 URL 类型参数
                    String attribMethod = null;

                    // find URL getter method
                    // 找到参数的 URL getter 方法，去找找到参数的URL属性 。例如，Invoker 有 `#getURL()` 方法。
                    LBL_PTS:
                    for (int i = 0; i < pts.length; ++i) {
                        Method[] ms = pts[i].getMethods();
                        for (Method m : ms) {
                            String name = m.getName();
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) {
                                urlTypeIndex = i;
                                attribMethod = name;
                                break LBL_PTS;
                            }
                        }
                    }
                    // 最终都没有找到 URL 类型的参数，也没有找到 URL 类型的属性，则抛出异常
                    if (attribMethod == null) {
                        throw new IllegalStateException("fail to create adaptive class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }

                    // Null point check
                    // 生成代码：校验 URL 非空
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                            urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    // 生成代码：生成获得 URL 的代码， `URL url = arg%d.%s();`
                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                }


                String[] value = adaptiveAnnotation.value();
                // value is not set, use the value generated from class name as the key
                // 没有设置Key，则使用“扩展点接口名的点分隔 作为Key
                if (value.length == 0) {
                    char[] charArray = type.getSimpleName().toCharArray();
                    StringBuilder sb = new StringBuilder(128);
                    for (int i = 0; i < charArray.length; i++) {
                        if (Character.isUpperCase(charArray[i])) {
                            if (i != 0) {
                                sb.append(".");
                            }
                            sb.append(Character.toLowerCase(charArray[i]));
                        } else {
                            sb.append(charArray[i]);
                        }
                    }
                    value = new String[]{sb.toString()};
                }

                // 判断是否有 Invocation 参数
                boolean hasInvocation = false;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {
                        // Null Point check
                        // 生成代码：校验 Invocation 非空
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        // 生成代码：获得方法名
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);

                        // 设置 hasInvocation 为 true，标记有 Invocation
                        hasInvocation = true;
                        break;
                    }
                }

                // 默认的扩展名
                String defaultExtName = cachedDefaultName;
                // 获得最终拓展名的代码字符串，例如：
                // 【简单】1. url.getParameter("proxy", "javassist")
                // 【复杂】2. url.getParameter(key1, url.getParameter(key2, defaultExtName))
                String getNameCode = null;
                for (int i = value.length - 1; i >= 0; --i) {// 倒序的原因，因为是顺序获取参数，参见【复杂】2. 的例子
                    if (i == value.length - 1) {
                        if (null != defaultExtName) {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    // 当【有】 Invocation 参数时，使用 `URL#getMethodParameter()` 方法。
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    // 当【没有】 Invocation 参数时，使用 `URL#getParameter()` 方法。
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                // 当属性名是 "protocol" ，使用 `URL#getProtocl()` 方法获取。
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                        } else {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                            else
                                getNameCode = "url.getProtocol()";
                        }
                    } else {
                        if (!"protocol".equals(value[i]))
                            if (hasInvocation)
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                        else
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                    }
                }
                // 生成代码：获取参数的代码。例如：String extName = url.getParameter("proxy", "javassist");
                code.append("\nString extName = ").append(getNameCode).append(";");
                // check extName == null?
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);
                // 生成代码：拓展对象，调用方法。例如
                // `com.alibaba.dubbo.rpc.ProxyFactory extension = (com.alibaba.dubbo.rpc.ProxyFactory) ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.ProxyFactory.class).getextension(extname);`
                s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
                code.append(s);

                // return statement
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }

                s = String.format("extension.%s(", method.getName());
                code.append(s);
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0)
                        code.append(", ");
                    code.append("arg").append(i);
                }
                code.append(");");
            }

            // 生成的方法
            codeBuilder.append("\npublic ").append(rt.getCanonicalName()).append(" ").append(method.getName()).append("(");
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) {
                    codeBuilder.append(", ");
                }
                codeBuilder.append(pts[i].getCanonicalName());
                codeBuilder.append(" ");
                codeBuilder.append("arg").append(i);
            }
            codeBuilder.append(")");
            if (ets.length > 0) { // 如果有异常抛出，则在方法上声明
                codeBuilder.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuilder.append(", ");
                    }
                    codeBuilder.append(ets[i].getCanonicalName());
                }
            }
            codeBuilder.append(" {");
            codeBuilder.append(code.toString());
            codeBuilder.append("\n}");
        }
        // 生成末尾的 }
        codeBuilder.append("\n}");

        // 调试，打印生成的代码
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuilder.toString());
        }
        return codeBuilder.toString();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}