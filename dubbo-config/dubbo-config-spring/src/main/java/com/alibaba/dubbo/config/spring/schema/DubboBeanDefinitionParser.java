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
package com.alibaba.dubbo.config.spring.schema;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.ArgumentConfig;
import com.alibaba.dubbo.config.ConsumerConfig;
import com.alibaba.dubbo.config.MethodConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ProviderConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.rpc.Protocol;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AbstractBeanDefinitionParser
 *
 * @export
 * DubboBeanDefinitionParser 实现了 BeanDefinitionParser 接口，定义了 Dubbo Bean 解析器
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {

    private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
    private static final Pattern GROUP_AND_VERION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
    /**
     * bean 对象的 Class 对象
     */
    private final Class<?> beanClass;
    /**
     * 该属性标识在 Bean 没有 id 属性时，是否自动生成该 id
     * 无需被其他应用引用的配置 Bean，可以不需要该 id
     */
    private final boolean required;

    public DubboBeanDefinitionParser(Class<?> beanClass, boolean required) {
        this.beanClass = beanClass;
        this.required = required;
    }

    /**
     * 解析 Dubbo XML 的方法
     * @param element XML 元素
     * @param parserContext 解析环境
     * @param beanClass Bean Class
     * @param required Bean 是否自动生成 id 属性
     * @return
     */
    @SuppressWarnings("unchecked")
    private static BeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass, boolean required) {
        // 创建 RootBeanDefinition 开始解析
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);
        // 懒加载，只有引用被注入到其他 Bean，或者被 getBean() 获取才开始进行初始化
        // 懒加载是缺省配置
        // 如果希望引用立即生成动态代理，可以通过配置 <dubbo:reference ... init = "true /> 实现
        beanDefinition.setLazyInit(false);
        // 开始解析配置对象的 id，如果配置的 required 为 true，且 id 为空，则立即生成 id
        String id = element.getAttribute("id");
        if ((id == null || id.length() == 0) && required) {
            // 通过 BeanName 自动生成 id，不同的配置对象不一样的
            String generatedBeanName = element.getAttribute("name");
            if (generatedBeanName == null || generatedBeanName.length() == 0) {
                if (ProtocolConfig.class.equals(beanClass)) {
                    generatedBeanName = "dubbo";
                } else {
                    generatedBeanName = element.getAttribute("interface");
                }
            }
            if (generatedBeanName == null || generatedBeanName.length() == 0) {
                generatedBeanName = beanClass.getName();
            }
            // 如果 id 在 Spring 的注册表中已经存在，则通过添加自增序列后缀解决重复问题
            id = generatedBeanName;
            int counter = 2;
            while (parserContext.getRegistry().containsBeanDefinition(id)) {
                id = generatedBeanName + (counter++);
            }
        }
        if (id != null && id.length() > 0) {
            if (parserContext.getRegistry().containsBeanDefinition(id)) {
                throw new IllegalStateException("Duplicate spring bean id " + id);
            }
            // 添加到 Spring 的注册表中
            parserContext.getRegistry().registerBeanDefinition(id, beanDefinition);
            // 设置 Bean 的 id 值
            beanDefinition.getPropertyValues().addPropertyValue("id", id);
        }
        // 处理 <dubbo:protocol /> 标签的特殊情况
        if (ProtocolConfig.class.equals(beanClass)) {
            // 例如,顺序是下面这样的
            // <dubbo:service interface="com.alibaba.dubbo.demo.DemoService" protocol="dubbo" ref="demoService"/>
            // <dubbo:protocol id="dubbo" name="dubbo" port="20880"/>
            for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {
                BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name);
                PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol");
                if (property != null) {
                    Object value = property.getValue();
                    if (value instanceof ProtocolConfig && id.equals(((ProtocolConfig) value).getName())) {
                        definition.getPropertyValues().addPropertyValue("protocol", new RuntimeBeanReference(id));
                    }
                }
            }
            // 处理 <dubbo:service /> 的 class 属性配置
            // <dubbo:class = "" /> 该标签在大多数情况下都是不建议使用的，官方文档也没有对应的解释说明
            // 配置了 class 属性，会直接自动创建 ServiceBean，而不需要使用 ref 配置指向对应的 ServiceBean
        } else if (ServiceBean.class.equals(beanClass)) {
            // 解析 class 的值，例如： 例如  <dubbo:service id="sa" interface="com.alibaba.dubbo.demo.DemoService" class="com.alibaba.dubbo.demo.provider.DemoServiceImpl" >
            String className = element.getAttribute("class");
            if (className != null && className.length() > 0) {
                // 创建 Service 的 RootBeanDefinition 对象
                // 相当于内嵌了 <bean class="com.alibaba.dubbo.demo.provider.DemoServiceImpl" />
                RootBeanDefinition classDefinition = new RootBeanDefinition();
                classDefinition.setBeanClass(ReflectUtils.forName(className));
                classDefinition.setLazyInit(false);
                // 解析 Service Bean 对象的属性
                parseProperties(element.getChildNodes(), classDefinition);
                // 设置 `<dubbo:service ref="" />` 属性
                beanDefinition.getPropertyValues().addPropertyValue("ref", new BeanDefinitionHolder(classDefinition, id + "Impl"));
            }
        //    解析 `<dubbo:provider />` 的内嵌子元素 `<dubbo:service />`
        } else if (ProviderConfig.class.equals(beanClass)) {
            // 解析内嵌指向的 XML 子元素
            parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
        } else if (ConsumerConfig.class.equals(beanClass)) {
            parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id, beanDefinition);
        }
        // 已解析的属性集合
        Set<String> props = new HashSet<String>();
        ManagedMap parameters = null;
        // 循环 Bean 对象的 setter 方法，将属性添加到 Bean 对象的属性赋值
        for (Method setter : beanClass.getMethods()) {
            String name = setter.getName();
            if (name.length() > 3 && name.startsWith("set")
                    && Modifier.isPublic(setter.getModifiers())
                    && setter.getParameterTypes().length == 1) {  // 判断符合 public 唯一参数的 setter 方法
                Class<?> type = setter.getParameterTypes()[0];
                // 添加 `props`
                String propertyName = name.substring(3, 4).toLowerCase() + name.substring(4);
                String property = StringUtils.camelToSplitName(propertyName, "-");
                // getter && public && 属性值类型与 setter 参数类型一值
                props.add(property);
                Method getter = null;
                try {
                    getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    try {
                        getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
                    } catch (NoSuchMethodException e2) {
                    }
                }
                if (getter == null
                        || !Modifier.isPublic(getter.getModifiers())
                        || !type.equals(getter.getReturnType())) {
                    continue;
                }
                // 解析 `<dubbo:parameters />`
                if ("parameters".equals(property)) {
                    parameters = parseParameters(element.getChildNodes(), beanDefinition);
                    // 解析 `<dubbo:method />`
                } else if ("methods".equals(property)) {
                    parseMethods(id, element.getChildNodes(), beanDefinition, parserContext);
                    // 解析 `<dubbo:argument />`
                } else if ("arguments".equals(property)) {
                    parseArguments(id, element.getChildNodes(), beanDefinition, parserContext);
                } else {
                    String value = element.getAttribute(property);
                    if (value != null) {
                        value = value.trim();
                        if (value.length() > 0) {
                            // 处理 registry 属性
                            // 不想注册到注册中心的情况，即 `registry=N/A`
                            if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {
                                RegistryConfig registryConfig = new RegistryConfig();
                                registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);
                                beanDefinition.getPropertyValues().addPropertyValue(property, registryConfig);
                            //    多注册中心的处理
                            } else if ("registry".equals(property) && value.indexOf(',') != -1) {
                                parseMultiRef("registries", value, beanDefinition, parserContext);
                            //    多服务提供者的处理
                            } else if ("provider".equals(property) && value.indexOf(',') != -1) {
                                parseMultiRef("providers", value, beanDefinition, parserContext);
                            //    多协议的处理
                            } else if ("protocol".equals(property) && value.indexOf(',') != -1) {
                                parseMultiRef("protocols", value, beanDefinition, parserContext);
                            } else {
                                Object reference;
                                // 处理属性为基本类型
                                if (isPrimitive(type)) {
                                    // 兼容处理
                                    if ("async".equals(property) && "false".equals(value)
                                            || "timeout".equals(property) && "0".equals(value)
                                            || "delay".equals(property) && "0".equals(value)
                                            || "version".equals(property) && "0.0.0".equals(value)
                                            || "stat".equals(property) && "-1".equals(value)
                                            || "reliable".equals(property) && "false".equals(value)) {
                                        // backward compatibility for the default value in old version's xsd
                                        value = null;
                                    }
                                    reference = value;
                                    // 处理在 `<dubbo:provider />` 或者 `<dubbo:service />` 等多个标签上=上定义了 `protocol` 属性的兼容问题
                                //    "protocol" 代表的是指向的 <dubbo:protocol /> 的编号( id )。
                                //    优先以指向的 <dubbo:protocol /> 的编号( id ) 为准备，否则认为是协议名
                                //    如果带有 "protocol" 属性的标签先解析，直接创建 ProtocolConfig 对象并设置到 "protocol" 属性，在后面标签 <dubbo:protocol /> 解析后，进行覆盖
                                //    如果带有 "protocol" 属性的标签后解析，无需走上述流程
                                } else if ("protocol".equals(property)
                                        && ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(value)  // 存在该注册协议的实现
                                        && (!parserContext.getRegistry().containsBeanDefinition(value) // Spring 注册表中不存在该 `<dubbo:provider />` 的定义
                                        || !ProtocolConfig.class.getName().equals(parserContext.getRegistry().getBeanDefinition(value).getBeanClassName()))) { // Spring 注册表中存在该编号，但是类型不为 ProtocolConfig
                                    // 目前，`<dubbo:provider protocol="" />` 推荐独立成 `<dubbo:protocol />`
                                    if ("dubbo:provider".equals(element.getTagName())) {
                                        logger.warn("Recommended replace <dubbo:provider protocol=\"" + value + "\" ... /> to <dubbo:protocol name=\"" + value + "\" ... />");
                                    }
                                    // backward compatibility
                                    ProtocolConfig protocol = new ProtocolConfig();
                                    protocol.setName(value);
                                    reference = protocol;
                                //    处理 onreturn 属性
                                } else if ("onreturn".equals(property)) {
                                    // 按照 . 进行拆分
                                    int index = value.lastIndexOf(".");
                                    String returnRef = value.substring(0, index);
                                    String returnMethod = value.substring(index + 1);
                                    // 创建 RuntimeBeanReference ，指向回调的对象
                                    reference = new RuntimeBeanReference(returnRef);
                                    // 设置 `onreturnMethod` 到 BeanDefinition 的属性值
                                    beanDefinition.getPropertyValues().addPropertyValue("onreturnMethod", returnMethod);
                                //    处理 onthrow 属性
                                } else if ("onthrow".equals(property)) {
                                    // 按照 . 进行拆分
                                    int index = value.lastIndexOf(".");
                                    String throwRef = value.substring(0, index);
                                    String throwMethod = value.substring(index + 1);
                                    // 创建 RuntimeBeanReference ，指向回调的对象
                                    reference = new RuntimeBeanReference(throwRef);
                                    // 设置 `onthrowMethod` 到 BeanDefinition 的属性值
                                    beanDefinition.getPropertyValues().addPropertyValue("onthrowMethod", throwMethod);
                                //    处理 oninvoke 属性
                                } else if ("oninvoke".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String invokeRef = value.substring(0, index);
                                    String invokeRefMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(invokeRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("oninvokeMethod", invokeRefMethod);
                                //    通用解析
                                } else {
                                    // 指向的 Service 的 Bean 对象，必须是单例
                                    if ("ref".equals(property) && parserContext.getRegistry().containsBeanDefinition(value)) {
                                        BeanDefinition refBean = parserContext.getRegistry().getBeanDefinition(value);
                                        if (!refBean.isSingleton()) {
                                            throw new IllegalStateException("The exported service ref " + value + " must be singleton! Please set the " + value + " bean scope to singleton, eg: <bean id=\"" + value + "\" scope=\"singleton\" ...>");
                                        }
                                    }
                                    // 创建 RuntimeBeanReference ，指向 Service 的 Bean 对象
                                    reference = new RuntimeBeanReference(value);
                                }
                                // 设置 BeanDefinition 的属性值
                                beanDefinition.getPropertyValues().addPropertyValue(propertyName, reference);
                            }
                        }
                    }
                }
            }
        }
        // 将 XML 元素，未在上面遍历到的属性，添加到 `parameters` 集合中
        // 目前测试下来，不存在这样的情况
        NamedNodeMap attributes = element.getAttributes();
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) {
            Node node = attributes.item(i);
            String name = node.getLocalName();
            if (!props.contains(name)) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String value = node.getNodeValue();
                parameters.put(name, new TypedStringValue(value, String.class));
            }
        }
        if (parameters != null) {
            beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
        }
        return beanDefinition;
    }

    /**
     * 处理属性是基本类型的情况
     * @param cls
     * @return
     */
    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive() || cls == Boolean.class || cls == Byte.class
                || cls == Character.class || cls == Short.class || cls == Integer.class
                || cls == Long.class || cls == Float.class || cls == Double.class
                || cls == String.class || cls == Date.class || cls == Class.class;
    }

    /**
     * 解析多指向的情况，如多注册中心、多协议、多服务提供者
     * @param property 属性
     * @param value 属性值
     * @param beanDefinition Bean 定义对象
     * @param parserContext 解析的环境
     */
    @SuppressWarnings("unchecked")
    private static void parseMultiRef(String property, String value, RootBeanDefinition beanDefinition,
                                      ParserContext parserContext) {
        String[] values = value.split("\\s*[,]+\\s*");
        ManagedList list = null;
        for (int i = 0; i < values.length; i++) {
            String v = values[i];
            if (v != null && v.length() > 0) {
                if (list == null) {
                    list = new ManagedList();
                }
                list.add(new RuntimeBeanReference(v));
            }
        }
        beanDefinition.getPropertyValues().addPropertyValue(property, list);
    }

    /**
     * 解析内嵌的指向的 XML 子元素
     * @param element 父 XML 元素
     * @param parserContext Spring Context
     * @param beanClass 内嵌解析子元素的 Bean 类
     * @param required 是否需要 Bean 的 id 属性
     * @param tag 标签
     * @param property 父 Bean 在子元素的属性名
     * @param ref 引用
     * @param beanDefinition 父 Bean 定义的对象
     */
    private static void parseNested(Element element, ParserContext parserContext, Class<?> beanClass, boolean required, String tag, String property, String ref, BeanDefinition beanDefinition) {
        NodeList nodeList = element.getChildNodes();
        if (nodeList != null && nodeList.getLength() > 0) {
            boolean first = true;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if (tag.equals(node.getNodeName())
                            || tag.equals(node.getLocalName())) { // 判断解析的元素是否是要进行解析的元素
                        if (first) {  // todo default 是干啥的
                            first = false;
                            String isDefault = element.getAttribute("default");
                            if (isDefault == null || isDefault.length() == 0) {
                                beanDefinition.getPropertyValues().addPropertyValue("default", "false");
                            }
                        }
                        // 解析子元素，创建 BeanDefinition 对象
                        BeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, required);
                        // 设置子 BeanDefinition，指向父 BeanDefinition 对象
                        if (subDefinition != null && ref != null && ref.length() > 0) {
                            subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析 Service Bean 对象的属性
     * @param nodeList 子元素节点数组
     * @param beanDefinition Bean 定义对象
     */
    private static void parseProperties(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    // 只解析 property 属性
                    if ("property".equals(node.getNodeName())
                            || "property".equals(node.getLocalName())) {
                        String name = ((Element) node).getAttribute("name");
                        // 优先使用 value 值，否则使用 ref 值
                        if (name != null && name.length() > 0) {
                            String value = ((Element) node).getAttribute("value");
                            String ref = ((Element) node).getAttribute("ref");
                            if (value != null && value.length() > 0) {
                                beanDefinition.getPropertyValues().addPropertyValue(name, value);
                            } else if (ref != null && ref.length() > 0) {
                                beanDefinition.getPropertyValues().addPropertyValue(name, new RuntimeBeanReference(ref));
                            } else {
                                throw new UnsupportedOperationException("Unsupported <property name=\"" + name + "\"> sub tag, Only supported <property name=\"" + name + "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     *  解析 `<dubbo:parameter />`
     * @param nodeList 子元素节点数组
     * @param beanDefinition Bean 定义对象
     * @return
     */
    @SuppressWarnings("unchecked")
    private static ManagedMap parseParameters(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedMap parameters = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if ("parameter".equals(node.getNodeName())
                            || "parameter".equals(node.getLocalName())) { // 这三个判断条件，只解析子元素中的 `<dubbo:parameter />`
                        if (parameters == null) {
                            parameters = new ManagedMap();
                        }
                        // 添加到参数集合
                        String key = ((Element) node).getAttribute("key");
                        String value = ((Element) node).getAttribute("value");
                        // todo <dubbo:parameter hide=“” /> 的用途
                        boolean hide = "true".equals(((Element) node).getAttribute("hide"));
                        if (hide) {
                            key = Constants.HIDE_KEY_PREFIX + key;
                        }
                        parameters.put(key, new TypedStringValue(value, String.class));
                    }
                }
            }
            return parameters;
        }
        return null;
    }

    /**
     * 解析 `<dubbo:method />`
     * @param id Bean 的 id 属性
     * @param nodeList 子元素节点数组
     * @param beanDefinition Bean 定义对象
     * @param parserContext 解析的上下文对象
     */
    @SuppressWarnings("unchecked")
    private static void parseMethods(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                     ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList methods = null; // 需要解析的方法数组
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    if ("method".equals(node.getNodeName()) || "method".equals(node.getLocalName())) {  // 这三条件，判断只解析 `<dubbo:method />`
                        String methodName = element.getAttribute("name");
                        // 方法名不为空
                        if (methodName == null || methodName.length() == 0) {
                            throw new IllegalStateException("<dubbo:method> name attribute == null");
                        }
                        if (methods == null) {
                            methods = new ManagedList();
                        }
                        // 解析 `<dubbo:method />`，创建 BeanDefinition 对象
                        BeanDefinition methodBeanDefinition = parse(((Element) node),
                                parserContext, MethodConfig.class, false);
                        // 添加到 `methods` 中
                        String name = id + "." + methodName;
                        BeanDefinitionHolder methodBeanDefinitionHolder = new BeanDefinitionHolder(
                                methodBeanDefinition, name);
                        methods.add(methodBeanDefinitionHolder);
                    }
                }
            }
            if (methods != null) {
                beanDefinition.getPropertyValues().addPropertyValue("methods", methods);
            }
        }
    }

    /**
     * 解析 `<dubbo:argument />`
     * @param id Bean 的 id 属性
     * @param nodeList 子元素的节点数组
     * @param beanDefinition Bean 定义
     * @param parserContext context
     */
    @SuppressWarnings("unchecked")
    private static void parseArguments(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                       ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList arguments = null; // 解析的参数数组
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    if ("argument".equals(node.getNodeName()) || "argument".equals(node.getLocalName())) { // 这三个判断条件确定只解析  `<dubbo:argument />`
                        String argumentIndex = element.getAttribute("index");
                        if (arguments == null) {
                            arguments = new ManagedList();
                        }
                        // 解析 `<dubbo:argument />`，创建 BeanDefinition 对象
                        BeanDefinition argumentBeanDefinition = parse(((Element) node),
                                parserContext, ArgumentConfig.class, false);
                        // 添加到 `arguments` 中
                        String name = id + "." + argumentIndex;
                        BeanDefinitionHolder argumentBeanDefinitionHolder = new BeanDefinitionHolder(
                                argumentBeanDefinition, name);
                        arguments.add(argumentBeanDefinitionHolder);
                    }
                }
            }
            if (arguments != null) {
                beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);
            }
        }
    }

    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {
        return parse(element, parserContext, beanClass, required);
    }

}