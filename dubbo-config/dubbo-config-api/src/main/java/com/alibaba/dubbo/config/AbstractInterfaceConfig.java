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
package com.alibaba.dubbo.config;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.*;
import com.alibaba.dubbo.config.support.Parameter;
import com.alibaba.dubbo.monitor.MonitorFactory;
import com.alibaba.dubbo.monitor.MonitorService;
import com.alibaba.dubbo.registry.RegistryFactory;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.InvokerListener;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.support.MockInvoker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidLocalHost;

/**
 * AbstractDefaultConfig
 *
 * @export
 *
 * AbstractInterfaceConfig 继承 AbstractMethodConfig ，抽象接口配置类
 *
 */
public abstract class AbstractInterfaceConfig extends AbstractMethodConfig {

    private static final long serialVersionUID = -1559314110797223229L;

    // local impl class name for the service interface
    protected String local;

    // local stub class name for the service interface
    protected String stub;

    // service monitor
    protected MonitorConfig monitor;

    // proxy type
    protected String proxy;

    // cluster type
    protected String cluster;

    // filter
    protected String filter;

    // listener
    protected String listener;

    // owner
    protected String owner;

    // connection limits, 0 means shared connection, otherwise it defines the connections delegated to the
    // current service
    protected Integer connections;

    // layer
    protected String layer;

    // application info
    protected ApplicationConfig application;

    // module info
    protected ModuleConfig module;

    // registry centers
    protected List<RegistryConfig> registries;

    // connection events
    protected String onconnect;

    // disconnection events
    protected String ondisconnect;

    // callback limits
    private Integer callbacks;

    // the scope for referring/exporting a service, if it's local, it means searching in current JVM only.
    private String scope;

    /**
     * 校验 RegistryConfig 配置数组
     * 实际上，该方法会初始化 RegistryConfig 的配置属性
     */
    protected void checkRegistry() {
        // 当 RegistryConfig 对象数组为空时，若有 `dubbo.registry.address` 配置，进行创建
        // for backward compatibility 向后兼容
        if (registries == null || registries.isEmpty()) {
            String address = ConfigUtils.getProperty("dubbo.registry.address");
            if (address != null && address.length() > 0) {
                registries = new ArrayList<RegistryConfig>();
                String[] as = address.split("\\s*[|]+\\s*");
                for (String a : as) {
                    RegistryConfig registryConfig = new RegistryConfig();
                    registryConfig.setAddress(a);
                    registries.add(registryConfig);
                }
            }
        }
        if ((registries == null || registries.isEmpty())) {
            throw new IllegalStateException((getClass().getSimpleName().startsWith("Reference")
                    ? "No such any registry to refer service in consumer "
                    : "No such any registry to export service in provider ")
                    + NetUtils.getLocalHost()
                    + " use dubbo version "
                    + Version.getVersion()
                    + ", Please add <dubbo:registry address=\"...\" /> to your spring config. If you want unregister, please set <dubbo:service registry=\"N/A\" />");
        }
        // 读取环境变量和 properties 配置到 RegistryConfig 对象数组
        for (RegistryConfig registryConfig : registries) {
            appendProperties(registryConfig);
        }
    }

    /**
     * 校验 ApplicationConfig 配置
     * 实际上，该方法会初始化 ApplicationConfig 的配置属性
     */
    @SuppressWarnings("deprecation")
    protected void checkApplication() {
        // 当 ApplicationConfig 对象为空时，若有 dubbo.application.name 配置，进行创建
        // for backward compatibility
        if (application == null) {
            String applicationName = ConfigUtils.getProperty("dubbo.application.name");
            if (applicationName != null && applicationName.length() > 0) {
                application = new ApplicationConfig();
            }
        }
        if (application == null) {
            throw new IllegalStateException(
                    "No such application config! Please add <dubbo:application name=\"...\" /> to your spring config.");
        }
        // 读取环境变量和 properties 配置到 ApplicationConfig 对象
        appendProperties(application);

        // 初始化优雅停机的超时时长
        String wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_KEY);
        if (wait != null && wait.trim().length() > 0) {
            System.setProperty(Constants.SHUTDOWN_WAIT_KEY, wait.trim());
        } else {
            wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY);
            if (wait != null && wait.trim().length() > 0) {
                System.setProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY, wait.trim());
            }
        }
    }

    /**
     * 加载注册中心 com.alibaba.dubbo.common.URL 数组
     * @param provider 是否是本地服务提供
     * @return
     */
    protected List<URL> loadRegistries(boolean provider) {
        // 校验 RegistryConfig 配置数组
        checkRegistry();
        List<URL> registryList = new ArrayList<URL>();
        if (registries != null && !registries.isEmpty()) {
            for (RegistryConfig config : registries) {
                // 获取注册中心的地址
                // address 可以使用 “|” 或者 “;” 作为分隔符，设置多个注册中心分组
                // 需要注意的是，一个注册中心集群是一个分组，而不是多个
                String address = config.getAddress();
                if (address == null || address.length() == 0) {
                    address = Constants.ANYHOST_VALUE;
                }
                // 从启动参数中获取
                String sysaddress = System.getProperty("dubbo.registry.address");
                if (sysaddress != null && sysaddress.length() > 0) {
                    address = sysaddress;
                }
                // 如果是有效的地址
                if (address.length() > 0 && !RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(address)) {
                    // 将各种配置对象加入到 Map 集合中
                    Map<String, String> map = new HashMap<String, String>();
                    appendParameters(map, application);
                    appendParameters(map, config);
                    // 添加 `path` `dubbo` `timestamp` `pid` 到 `map` 集合中
                    map.put("path", RegistryService.class.getName());
                    map.put("dubbo", Version.getProtocolVersion());
                    map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
                    if (ConfigUtils.getPid() > 0) {
                        map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
                    }
                    // 若不存在 `protocol` 参数，默认 "dubbo" 添加到 `map` 集合中
                    if (!map.containsKey("protocol")) {
                        if (ExtensionLoader.getExtensionLoader(RegistryFactory.class).hasExtension("remote")) {
                            map.put("protocol", "remote");
                        } else {
                            map.put("protocol", "dubbo");
                        }
                    }
                    // 解析地址，创建 Dubbo URL 数组。（数组大小可以为一）
                    List<URL> urls = UrlUtils.parseURLs(address, map);
                    // 循环 `url` ，设置 "registry" 和 "protocol" 属性
                    for (URL url : urls) {
                        url = url.addParameter(Constants.REGISTRY_KEY, url.getProtocol());
                        url = url.setProtocol(Constants.REGISTRY_PROTOCOL);
                        if ((provider && url.getParameter(Constants.REGISTER_KEY, true))  // 服务提供者 & 注册
                                || (!provider && url.getParameter(Constants.SUBSCRIBE_KEY, true))) {  // 服务消费者 & 订阅
                            // 如果是服务提供者，判断是否是只注册不注册，如果是，就不添加结果到 registryList 中
                            // 如果是服务消费者，判断是否是只注册不订阅，如果是，也不添加结果到 registryList
                            registryList.add(url);
                        }
                    }
                }
            }
        }
        return registryList;
    }

    /**
     * 加载监控中心
     * @param registryURL
     * @return
     */
    protected URL loadMonitor(URL registryURL) {
        // 当监控中线不存在的时候
        if (monitor == null) {
            // 从配置文件中获取的是监控中心的 ip 地址
            String monitorAddress = ConfigUtils.getProperty("dubbo.monitor.address");
            // 从配置中获取监控中心的 协议
            String monitorProtocol = ConfigUtils.getProperty("dubbo.monitor.protocol");
            // 未从配置中获取监控中心的地址和协议，无法创建监控中心的配置对象
            if ((monitorAddress == null || monitorAddress.length() == 0) && (monitorProtocol == null || monitorProtocol.length() == 0)) {
                return null;
            }

            // 创建监控中心的配置对象，并赋值属性
            monitor = new MonitorConfig();
            if (monitorAddress != null && monitorAddress.length() > 0) {
                monitor.setAddress(monitorAddress);
            }
            if (monitorProtocol != null && monitorProtocol.length() > 0) {
                monitor.setProtocol(monitorProtocol);
            }
        }
        // 将根据读取的监控中心配置文件得到的数据创建的监控中心配置对象添加进来
        appendProperties(monitor);
        Map<String, String> map = new HashMap<String, String>();
        // 接口名
        map.put(Constants.INTERFACE_KEY, MonitorService.class.getName());
        map.put(Constants.SIDE_KEY, Constants.CONSUMER_SIDE); // 设置 Side，消费者
        map.put("dubbo", Version.getProtocolVersion()); // 设置协议版本
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis())); // 时间戳
        if (ConfigUtils.getPid() > 0) { // pid 进程号
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        //set ip 设置 ip
        String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry == null || hostToRegistry.length() == 0) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        map.put(Constants.REGISTER_IP_KEY, hostToRegistry);
        // 将监控中心的配置对象添加到 URL 的参数集合中
        appendParameters(map, monitor);
        // 将 Application 的配置对象添加到 URL 的参数集合中
        appendParameters(map, application);
        String address = monitor.getAddress();
        String sysaddress = System.getProperty("dubbo.monitor.address");
        if (sysaddress != null && sysaddress.length() > 0) {
            address = sysaddress;
        }
        // 配置直连监控中心的 address
        if (ConfigUtils.isNotEmpty(address)) {
            // 如果配置参数集合里面没有指定协议，就使用默认的 dubbo 协议
            if (!map.containsKey(Constants.PROTOCOL_KEY)) {
                if (ExtensionLoader.getExtensionLoader(MonitorFactory.class).hasExtension("logstat")) {
                    map.put(Constants.PROTOCOL_KEY, "logstat");
                } else {
                    map.put(Constants.PROTOCOL_KEY, "dubbo");
                }
            }
            // 解析参数集合为 URL并返回
            return UrlUtils.parseURL(address, map);
        } else if (Constants.REGISTRY_PROTOCOL.equals(monitor.getProtocol()) && registryURL != null) {
            // 从注册中心发现监控中心
            return registryURL.setProtocol("dubbo").addParameter(Constants.PROTOCOL_KEY, "registry").addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map));
        }
        return null;
    }

    /**
     * 校验接口和方法：
     *  1. 接口类非空，说明是接口
     *  2. 方法在接口中已经定义
     * @param interfaceClass 接口类
     * @param methods 方法数组
     */
    protected void checkInterfaceAndMethods(Class<?> interfaceClass, List<MethodConfig> methods) {
        // interface cannot be null
        if (interfaceClass == null) {
            throw new IllegalStateException("interface not allow null!");
        }
        // to verify interfaceClass is an interface
        if (!interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        // check if methods exist in the interface
        if (methods != null && !methods.isEmpty()) {
            for (MethodConfig methodBean : methods) {
                String methodName = methodBean.getName();
                if (methodName == null || methodName.length() == 0) {
                    throw new IllegalStateException("<dubbo:method> name attribute is required! Please check: <dubbo:service interface=\"" + interfaceClass.getName() + "\" ... ><dubbo:method name=\"\" ... /></<dubbo:reference>");
                }
                boolean hasMethod = false;
                for (java.lang.reflect.Method method : interfaceClass.getMethods()) {
                    if (method.getName().equals(methodName)) {
                        hasMethod = true;
                        break;
                    }
                }
                if (!hasMethod) {
                    throw new IllegalStateException("The interface " + interfaceClass.getName()
                            + " not found method " + methodName);
                }
            }
        }
    }

    /**
     * 校验 Mock 相关的配置
     * @param interfaceClass
     */
    void checkMock(Class<?> interfaceClass) {
        if (ConfigUtils.isEmpty(mock)) {
            return;
        }

        String normalizedMock = MockInvoker.normalizeMock(mock);
        if (normalizedMock.startsWith(Constants.RETURN_PREFIX)) {
            normalizedMock = normalizedMock.substring(Constants.RETURN_PREFIX.length()).trim();
            try {
                MockInvoker.parseMockValue(normalizedMock);
            } catch (Exception e) {
                throw new IllegalStateException("Illegal mock return in <dubbo:service/reference ... " +
                        "mock=\"" + mock + "\" />");
            }
        } else if (normalizedMock.startsWith(Constants.THROW_PREFIX)) {
            normalizedMock = normalizedMock.substring(Constants.THROW_PREFIX.length()).trim();
            if (ConfigUtils.isNotEmpty(normalizedMock)) {
                try {
                    MockInvoker.getThrowable(normalizedMock);
                } catch (Exception e) {
                    throw new IllegalStateException("Illegal mock throw in <dubbo:service/reference ... " +
                            "mock=\"" + mock + "\" />");
                }
            }
        } else {
            MockInvoker.getMockObject(normalizedMock, interfaceClass);
        }
    }

    /**
     * 校验 Stub 相关的配置
     */
    void checkStub(Class<?> interfaceClass) {
        if (ConfigUtils.isNotEmpty(local)) {
            Class<?> localClass = ConfigUtils.isDefault(local) ? ReflectUtils.forName(interfaceClass.getName() + "Local") : ReflectUtils.forName(local);
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceClass.getName());
            }
            try {
                ReflectUtils.findConstructor(localClass, interfaceClass);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() + "(" + interfaceClass.getName() + ")\" in local implementation class " + localClass.getName());
            }
        }
        if (ConfigUtils.isNotEmpty(stub)) {
            Class<?> localClass = ConfigUtils.isDefault(stub) ? ReflectUtils.forName(interfaceClass.getName() + "Stub") : ReflectUtils.forName(stub);
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceClass.getName());
            }
            try {
                ReflectUtils.findConstructor(localClass, interfaceClass);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() + "(" + interfaceClass.getName() + ")\" in local implementation class " + localClass.getName());
            }
        }
    }

    /**
     * @return local
     * @deprecated Replace to <code>getStub()</code>
     */
    @Deprecated
    public String getLocal() {
        return local;
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(Boolean)</code>
     */
    @Deprecated
    public void setLocal(Boolean local) {
        if (local == null) {
            setLocal((String) null);
        } else {
            setLocal(String.valueOf(local));
        }
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(String)</code>
     */
    @Deprecated
    public void setLocal(String local) {
        checkName("local", local);
        this.local = local;
    }

    public String getStub() {
        return stub;
    }

    public void setStub(Boolean stub) {
        if (stub == null) {
            setStub((String) null);
        } else {
            setStub(String.valueOf(stub));
        }
    }

    public void setStub(String stub) {
        checkName("stub", stub);
        this.stub = stub;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        checkExtension(Cluster.class, "cluster", cluster);
        this.cluster = cluster;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        checkExtension(ProxyFactory.class, "proxy", proxy);
        this.proxy = proxy;
    }

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    @Parameter(key = Constants.REFERENCE_FILTER_KEY, append = true)
    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        checkMultiExtension(Filter.class, "filter", filter);
        this.filter = filter;
    }

    @Parameter(key = Constants.INVOKER_LISTENER_KEY, append = true)
    public String getListener() {
        return listener;
    }

    public void setListener(String listener) {
        checkMultiExtension(InvokerListener.class, "listener", listener);
        this.listener = listener;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        checkNameHasSymbol("layer", layer);
        this.layer = layer;
    }

    public ApplicationConfig getApplication() {
        return application;
    }

    public void setApplication(ApplicationConfig application) {
        this.application = application;
    }

    public ModuleConfig getModule() {
        return module;
    }

    public void setModule(ModuleConfig module) {
        this.module = module;
    }

    public RegistryConfig getRegistry() {
        return registries == null || registries.isEmpty() ? null : registries.get(0);
    }

    public void setRegistry(RegistryConfig registry) {
        List<RegistryConfig> registries = new ArrayList<RegistryConfig>(1);
        registries.add(registry);
        this.registries = registries;
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    @SuppressWarnings({"unchecked"})
    public void setRegistries(List<? extends RegistryConfig> registries) {
        this.registries = (List<RegistryConfig>) registries;
    }

    public MonitorConfig getMonitor() {
        return monitor;
    }

    public void setMonitor(String monitor) {
        this.monitor = new MonitorConfig(monitor);
    }

    public void setMonitor(MonitorConfig monitor) {
        this.monitor = monitor;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        checkMultiName("owner", owner);
        this.owner = owner;
    }

    public Integer getCallbacks() {
        return callbacks;
    }

    public void setCallbacks(Integer callbacks) {
        this.callbacks = callbacks;
    }

    public String getOnconnect() {
        return onconnect;
    }

    public void setOnconnect(String onconnect) {
        this.onconnect = onconnect;
    }

    public String getOndisconnect() {
        return ondisconnect;
    }

    public void setOndisconnect(String ondisconnect) {
        this.ondisconnect = ondisconnect;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

}
