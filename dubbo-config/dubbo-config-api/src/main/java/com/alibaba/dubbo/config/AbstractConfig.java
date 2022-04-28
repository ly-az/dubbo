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
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.CollectionUtils;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.support.Parameter;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods and public methods for parsing configuration
 *
 * @export
 *
 * 抽象配置类，除 ArgumentConfig 类之外，所有的配置类都继承该类
 * 主要提供配置解析与校验的方法
 *
 */
public abstract class AbstractConfig implements Serializable {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractConfig.class);
    private static final long serialVersionUID = 4267533505537413570L;
    private static final int MAX_LENGTH = 200;

    private static final int MAX_PATH_LENGTH = 200;

    private static final Pattern PATTERN_NAME = Pattern.compile("[\\-._0-9a-zA-Z]+");

    private static final Pattern PATTERN_MULTI_NAME = Pattern.compile("[,\\-._0-9a-zA-Z]+");

    private static final Pattern PATTERN_METHOD_NAME = Pattern.compile("[a-zA-Z][0-9a-zA-Z]*");

    private static final Pattern PATTERN_PATH = Pattern.compile("[/\\-$._0-9a-zA-Z]+");

    private static final Pattern PATTERN_NAME_HAS_SYMBOL = Pattern.compile("[:*,\\s/\\-._0-9a-zA-Z]+");

    private static final Pattern PATTERN_KEY = Pattern.compile("[*,\\-._0-9a-zA-Z]+");
    /**
     * 新老版本的 properties 的 key 映射
     * key 新版本的配置映射
     * value 旧版本的配置映射
     */
    private static final Map<String, String> legacyProperties = new HashMap<String, String>();
    /**
     * 配置类名的后缀
     * 例如，ServiceConfig 的后缀就是 Config，ServiceBean 的后缀就是 Bean
     */
    private static final String[] SUFFIXES = new String[]{"Config", "Bean"};

    static {
        legacyProperties.put("dubbo.protocol.name", "dubbo.service.protocol");
        legacyProperties.put("dubbo.protocol.host", "dubbo.service.server.host");
        legacyProperties.put("dubbo.protocol.port", "dubbo.service.server.port");
        legacyProperties.put("dubbo.protocol.threads", "dubbo.service.max.thread.pool.size");
        legacyProperties.put("dubbo.consumer.timeout", "dubbo.service.invoke.timeout");
        legacyProperties.put("dubbo.consumer.retries", "dubbo.service.max.retry.providers");
        legacyProperties.put("dubbo.consumer.check", "dubbo.service.allow.no.provider");
        legacyProperties.put("dubbo.service.url", "dubbo.service.address");

        // this is only for compatibility
        Runtime.getRuntime().addShutdownHook(DubboShutdownHook.getDubboShutdownHook());
    }

    /**
     * 配置对象的 id，适用于除了 API 配置之外的三种配置方式
     * 标记一个配置对象，可以用于对象之间的引用
     * 例如 XML 的 < dubbo:service provider="${PROVIDER_ID}" >
     *     provider 就是 < dubbo:provider > 的 id 属性
     *
     * 为什么不适用 API 配置呢？
     * API配置的话直接 #setXxx(config) 对象即可
     */
    protected String id;

    /**
     * 将键对应的值转换为对应的目标值
     * @param key 键
     * @param value 值
     * @return 转换之后的值
     */
    private static String convertLegacyValue(String key, String value) {
        if (value != null && value.length() > 0) {
            if ("dubbo.service.max.retry.providers".equals(key)) {
                return String.valueOf(Integer.parseInt(value) - 1);
            } else if ("dubbo.service.allow.no.provider".equals(key)) {
                return String.valueOf(!Boolean.parseBoolean(value));
            }
        }
        return value;
    }

    /**
     * 如果公共配置比较简单：没有多注册中心，多协议的情况下
     * 或者想要在多个 Spring 容器下共享配置，可以使用 dubbo.properties 作为缺省配置
     *
     * Dubbo在启动时自动加载 classpath 目录下的 dubbo.properties 配置
     * 可以通过 JVM 启动参数 `-Ddubbo.properties.files=xxx.properties 来修改改路径
     *
     * 属性配置是不支持多注册中心的，也不支持多协议
     * 外部化配置支持多注册中心、多协议
     *
     * 读取环境变量和 properties 配置到配置对象
     * @param config 配置对象
     */
    protected static void appendProperties(AbstractConfig config) {
        if (config == null) {
            return;
        }
        //  获取配置的前缀
        String prefix = "dubbo." + getTagName(config.getClass()) + ".";
        // 获取配置类的所有方法，通过反射获取配置类的属性名，再通过属性名读取【启动参数变量】和【 properties 配置】并设置到配置对象中
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                // public 级别的、且唯一参数为基本数据类型的 set 方法
                // 唯一参数为基本类型，决定了一个配置对象无法设置另外已鞥配置对象数组为属性，也就是没有多注册中心、多协议等情况
                // 例如：ServiceConfig 无法通过属性配置设置多个 ProtocolConfig 对象
                if (name.length() > 3 && name.startsWith("set") && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 1 && isPrimitive(method.getParameterTypes()[0])) {
                    // 获取属性名，例如 ApplicationConfig#setName() 获取的属性变量就是 name
                    String property = StringUtils.camelToSplitName(name.substring(3, 4).toLowerCase() + name.substring(4), ".");

                    // 获取【启动参数变量】，优先从带有 Config#id 的配置中获取，例如： 例如：`dubbo.application.demo-provider.name`
                    String value = null;
                    // 带有 `Config#id`
                    if (config.getId() != null && config.getId().length() > 0) {
                        String pn = prefix + config.getId() + "." + property;
                        value = System.getProperty(pn);
                        if (!StringUtils.isBlank(value)) {
                            logger.info("Use System Property " + pn + " to config dubbo");
                        }
                    }
                    // 【启动参数变量】获取不到，其次从不带有 `Config#id` 的配置中获取，例如：`dubbo.application.name`
                    // 不带有 `Config#id`
                    if (value == null || value.length() == 0) {
                        String pn = prefix + property;
                        value = System.getProperty(pn);
                        if (!StringUtils.isBlank(value)) {
                            logger.info("Use System Property " + pn + " to config dubbo");
                        }
                    }
                    // 覆盖优先级为：启动参数变量（通过 -D 指定） > XML 配置 > properties 配置，因此需要使用 getter 判断 XML 是否已经设置
                    // properties 配置通常相当于缺省值，常用于公共的配置
                    if (value == null || value.length() == 0) {
                        Method getter;
                        try {
                            getter = config.getClass().getMethod("get" + name.substring(3));
                        } catch (NoSuchMethodException e) {
                            try {
                                getter = config.getClass().getMethod("is" + name.substring(3));
                            } catch (NoSuchMethodException e2) {
                                getter = null;
                            }
                        }
                        if (getter != null) {
                            // 通过 getter 判断 是否已经配置了 XML
                            // 这是由于 XML 配置 的优先级高于 properties，调用getter 方法获取到配置，说明已经通过 XML 设置了配置值
                            if (getter.invoke(config) == null) {
                                // 获取【 properties 配置】，优先从带有 Config#id 的配置中获取，例如 dubbo.application.demo-provider.name
                                if (config.getId() != null && config.getId().length() > 0) {
                                    value = ConfigUtils.getProperty(prefix + config.getId() + "." + property);
                                }
                                // 【 properties 配置】获取不到，则其次从不带有 Config#id 的配置中获取，例如 dubbo.application.name
                                if (value == null || value.length() == 0) {
                                    value = ConfigUtils.getProperty(prefix + property);
                                }
                                // 【 properties 配置】获取，兼容旧版本配置，最后从不带有 Config#id 的配置中获取，例如 dubbo.protocol.name
                                if (value == null || value.length() == 0) {
                                    String legacyKey = legacyProperties.get(prefix + property);
                                    if (legacyKey != null && legacyKey.length() > 0) {
                                        value = convertLegacyValue(legacyKey, ConfigUtils.getProperty(legacyKey));
                                    }
                                }

                            }
                        }
                    }
                    // 获取到值后，通过反射进行设置
                    if (value != null && value.length() > 0) {
                        method.invoke(config, convertPrimitive(method.getParameterTypes()[0], value));
                    }
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * 获取类名对应的属性标签，例如，ServiceConfig 对应为 service
     * @param cls 类型
     * @return 属性标签
     */
    private static String getTagName(Class<?> cls) {
        String tag = cls.getSimpleName();
        for (String suffix : SUFFIXES) {
            if (tag.endsWith(suffix)) {
                tag = tag.substring(0, tag.length() - suffix.length());
                break;
            }
        }
        tag = tag.toLowerCase();
        return tag;
    }

    protected static void appendParameters(Map<String, String> parameters, Object config) {
        appendParameters(parameters, config, null);
    }

    /**
     * 该方法将配置对象的属性，添加到参数集合
     * @param parameters 参数集合，实际上该集合是用于 URL.parameters
     * @param config 配置对象
     * @param prefix 属性前缀，用于将配置项添加到 parameters 中的前缀
     */
    @SuppressWarnings("unchecked")
    protected static void appendParameters(Map<String, String> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        // 获取配置类下的所有的方法，为下面的反射获取配置项进行准备
        Method[] methods = config.getClass().getMethods();
        // 遍历上面所获取的方法数组
        for (Method method : methods) {
            try {
                String name = method.getName();
                // 获取返回值是基本类类型的、public 的 getting 方法
                if ((name.startsWith("get") || name.startsWith("is"))
                        && !"getClass".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && isPrimitive(method.getReturnType())) {
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    // 返回值类型为 Object 或被注解标注排除的( @Parameter.excluded=true )的配置项
                    if (method.getReturnType() == Object.class || parameter != null && parameter.excluded()) {
                        continue;
                    }
                    // 获取属性名
                    int i = name.startsWith("get") ? 3 : 2;
                    // 获取配置项名称
                    String prop = StringUtils.camelToSplitName(name.substring(i, i + 1).toLowerCase() + name.substring(i + 1), ".");
                    String key;
                    if (parameter != null && parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        key = prop;
                    }
                    // 获取属性值
                    Object value = method.invoke(config);
                    String str = String.valueOf(value).trim();
                    // 转义
                    if (value != null && str.length() > 0) {
                        if (parameter != null && parameter.escaped()) {
                            str = URL.encode(str);
                        }
                        // 拼接，详细说明参见 Parameter#append() 方法的说明
                        if (parameter != null && parameter.append()) {
                            //  default 获取，适用于 ServiceConfig =》ProviderConfig 、ReferenceConfig =》ConsumerConfig
                            String pre = parameters.get(Constants.DEFAULT_KEY + "." + key);
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }
                            // parameters 属性配置获取，例如 AbstractMethodConfig.parameters
                            pre = parameters.get(key);
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }
                        }
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        // 添加配置项
                        parameters.put(key, str);
                        // @Parameter.required = true 时进行非空校验
                    } else if (parameter != null && parameter.required()) {
                        throw new IllegalStateException(config.getClass().getSimpleName() + "." + key + " == null");
                    }
                    // 当方法名是 getParameters() 、public 的、没有形参、返回值是 Map
                } else if ("getParameters".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && method.getReturnType() == Map.class) {
                    //通过反射调用 getParameters() 方法
                    Map<String, String> map = (Map<String, String>) method.invoke(config, new Object[0]);
                    if (map != null && map.size() > 0) {
                        String pre = (prefix != null && prefix.length() > 0 ? prefix + "." : "");
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            // 将 map 添加到 parameters ，kv 格式为 prefix:entry.key entry.value
                            parameters.put(pre + entry.getKey().replace('-', '.'), entry.getValue());
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        // 这个方法就是通过反射获取配置类的 getParameters() 方法来获取对应的属性，动态地设置配置项，拓展出非 Dubbo 内置好的逻辑
    }

    protected static void appendAttributes(Map<Object, Object> parameters, Object config) {
        appendAttributes(parameters, config, null);
    }

    /**
     * 将 @Parameter(attribute = true) 注解的配置对象的属性，添加到参数集合
     * @param parameters 参数集合
     * @param config 配置对象
     * @param prefix 属性前缀
     */
    protected static void appendAttributes(Map<Object, Object> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                if ((name.startsWith("get") || name.startsWith("is"))
                        && !"getClass".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && isPrimitive(method.getReturnType())) {
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    if (parameter == null || !parameter.attribute())
                        continue;
                    String key;
                    parameter.key();
                    if (parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        int i = name.startsWith("get") ? 3 : 2;
                        key = name.substring(i, i + 1).toLowerCase() + name.substring(i + 1);
                    }
                    Object value = method.invoke(config);
                    if (value != null) {
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        parameters.put(key, value);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    private static boolean isPrimitive(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Character.class
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Object.class;
    }

    /**
     * 通过反射进行配置值设置
     * @param type 类型
     * @param value 配置值
     * @return 配置好的属性值值对象
     */
    private static Object convertPrimitive(Class<?> type, String value) {
        if (type == char.class || type == Character.class) {
            return value.length() > 0 ? value.charAt(0) : '\0';
        } else if (type == boolean.class || type == Boolean.class) {
            return Boolean.valueOf(value);
        } else if (type == byte.class || type == Byte.class) {
            return Byte.valueOf(value);
        } else if (type == short.class || type == Short.class) {
            return Short.valueOf(value);
        } else if (type == int.class || type == Integer.class) {
            return Integer.valueOf(value);
        } else if (type == long.class || type == Long.class) {
            return Long.valueOf(value);
        } else if (type == float.class || type == Float.class) {
            return Float.valueOf(value);
        } else if (type == double.class || type == Double.class) {
            return Double.valueOf(value);
        }
        return value;
    }

    protected static void checkExtension(Class<?> type, String property, String value) {
        checkName(property, value);
        if (value != null && value.length() > 0
                && !ExtensionLoader.getExtensionLoader(type).hasExtension(value)) {
            throw new IllegalStateException("No such extension " + value + " for " + property + "/" + type.getName());
        }
    }

    protected static void checkMultiExtension(Class<?> type, String property, String value) {
        checkMultiName(property, value);
        if (value != null && value.length() > 0) {
            String[] values = value.split("\\s*[,]+\\s*");
            for (String v : values) {
                if (v.startsWith(Constants.REMOVE_VALUE_PREFIX)) {
                    v = v.substring(1);
                }
                if (Constants.DEFAULT_KEY.equals(v)) {
                    continue;
                }
                if (!ExtensionLoader.getExtensionLoader(type).hasExtension(v)) {
                    throw new IllegalStateException("No such extension " + v + " for " + property + "/" + type.getName());
                }
            }
        }
    }

    protected static void checkLength(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, null);
    }

    protected static void checkPathLength(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, null);
    }

    protected static void checkName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME);
    }

    protected static void checkNameHasSymbol(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME_HAS_SYMBOL);
    }

    protected static void checkKey(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_KEY);
    }

    protected static void checkMultiName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_MULTI_NAME);
    }

    protected static void checkPathName(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, PATTERN_PATH);
    }

    protected static void checkMethodName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_METHOD_NAME);
    }

    protected static void checkParameterName(Map<String, String> parameters) {
        if (parameters == null || parameters.size() == 0) {
            return;
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            checkNameHasSymbol(entry.getKey(), entry.getValue());
        }
    }

    protected static void checkProperty(String property, String value, int maxlength, Pattern pattern) {
        if (value == null || value.length() == 0) {
            return;
        }
        if (value.length() > maxlength) {
            throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" is longer than " + maxlength);
        }
        if (pattern != null) {
            Matcher matcher = pattern.matcher(value);
            if (!matcher.matches()) {
                throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" contains illegal " +
                        "character, only digit, letter, '-', '_' or '.' is legal.");
            }
        }
    }

    @Parameter(excluded = true)
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * 读取注解用以获取配置对象
     * @param annotationClass 注解的 Class 对象
     * @param annotation 注解
     */
    protected void appendAnnotation(Class<?> annotationClass, Object annotation) {
        Method[] methods = annotationClass.getMethods();
        for (Method method : methods) {
            if (method.getDeclaringClass() != Object.class
                    && method.getReturnType() != void.class
                    && method.getParameterTypes().length == 0
                    && Modifier.isPublic(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())) {
                try {
                    String property = method.getName();
                    if ("interfaceClass".equals(property) || "interfaceName".equals(property)) {
                        property = "interface";
                    }
                    String setter = "set" + property.substring(0, 1).toUpperCase() + property.substring(1);
                    Object value = method.invoke(annotation);
                    if (!isAnnotationArray(method.getReturnType()) &&  value != null && !value.equals(method.getDefaultValue())) {
                        Class<?> parameterType = ReflectUtils.getBoxedClass(method.getReturnType());
                        if ("filter".equals(property) || "listener".equals(property)) {
                            parameterType = String.class;
                            value = StringUtils.join((String[]) value, ",");
                        } else if ("parameters".equals(property)) {
                            parameterType = Map.class;
                            value = CollectionUtils.toStringMap((String[]) value);
                        }
                        try {
                            Method setterMethod = getClass().getMethod(setter, parameterType);
                            setterMethod.invoke(this, value);
                        } catch (NoSuchMethodException e) {
                            // ignore
                        }
                    }
                } catch (Throwable e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

    boolean isAnnotationArray(Class target) {
        if (target.isArray() && target.getComponentType().isAnnotation()) {
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        try {
            StringBuilder buf = new StringBuilder();
            buf.append("<dubbo:");
            buf.append(getTagName(getClass()));
            Method[] methods = getClass().getMethods();
            for (Method method : methods) {
                try {
                    String name = method.getName();
                    if ((name.startsWith("get") || name.startsWith("is"))
                            && !"get".equals(name) && !"is".equals(name)
                            && !"getClass".equals(name) && !"getObject".equals(name)
                            && Modifier.isPublic(method.getModifiers())
                            && method.getParameterTypes().length == 0
                            && isPrimitive(method.getReturnType())) {
                        int i = name.startsWith("get") ? 3 : 2;
                        String key = name.substring(i, i + 1).toLowerCase() + name.substring(i + 1);
                        Object value = method.invoke(this);
                        if (value != null) {
                            buf.append(" ");
                            buf.append(key);
                            buf.append("=\"");
                            buf.append(value);
                            buf.append("\"");
                        }
                    }
                } catch (Exception e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            buf.append(" />");
            return buf.toString();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            return super.toString();
        }
    }

}
