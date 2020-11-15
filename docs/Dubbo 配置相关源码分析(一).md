# Dubbo 配置相关源码分析

在 Dubbo 中，所有配置分为3大类，

* 服务发现：表示该配置项用于服务的注册与发现，目的是让消费方找到提供方。
* 服务治理：表示该配置项用于治理服务间的关系，或为开发测试提供便利条件。
* 性能调优：表示该配置项用于调优性能，不同的选项对性能会产生影响。

所有配置最终都将转换为 Dubbo URL 表示，并由服务提供方生成，经注册中心传递给消费方，各属性对应 URL 的参数

## AbstractConfig

抽象配置类，除了 ArgumentConfig ，我们可以看到所有的配置类都继承该类。AbstractConfig 主要提供配置解析与校验相关的工具方法。

```java
protected String id;
```

id 属性，配置对象的编号，适用于除了 API 配置之外的三种配置方式（xml、注解、外部配置、API），标记一个配置对象，可用于对象之间的引用。例如 XML 的 <dubbo:service provider="${PROVIDER_ID}"> ，其中 provider 为 <dubbo:provider> 的 ID 属性。

那为什么说不适用 API 配置呢？直接 #setXXX(config) 对象即可。

在学习代码之前，先了解下[@Parameter](##@Parameter)和[URL](##URL)

```java
/**
* 将配置对象的属性，添加到参数集合
*
* @param parameters 参数集合 实际上，该集合会用于 URL.parameters 。
* @param config 配置对象
* @param prefix 属性前缀
*/

@SuppressWarnings("unchecked")
protected static void appendParameters(Map<String, String> parameters, Object config, String prefix) {
    if (config == null) {
        return;
    }
    // 获得所有方法的数组，为下面通过反射获得配置项的值做准备。
    Method[] methods = config.getClass().getMethods();
    // 循环每个方法。
    for (Method method : methods) {
        try {
            String name = method.getName();
            // 方法为获得基本类型 + public 的 getting 方法。
            if ((name.startsWith("get") || name.startsWith("is"))
                    && !"getClass".equals(name)
                    && Modifier.isPublic(method.getModifiers())
                    && method.getParameterTypes().length == 0
                    && isPrimitive(method.getReturnType())) { // 方法为获取基本类型，public 的 getting 方法。
                Parameter parameter = method.getAnnotation(Parameter.class);
                // 返回值类型为 Object 或排除( `@Parameter.exclue=true` )的配置项，跳过。
                if (method.getReturnType() == Object.class || parameter != null && parameter.excluded()) {
                    continue;
                }
                // 获得属性名 如果是get则为3 is则为2
                int i = name.startsWith("get") ? 3 : 2;
                String prop = StringUtils.camelToSplitName(name.substring(i, i + 1).toLowerCase() + name.substring(i + 1), ".");
                String key;
                if (parameter != null && parameter.key() != null && parameter.key().length() > 0) {
                    key = parameter.key();
                } else {
                    key = prop;
                }
                // 获得属性值
                Object value = method.invoke(config, new Object[0]);
                String str = String.valueOf(value).trim();
                if (value != null && str.length() > 0) {
                    // 转义
                    if (parameter != null && parameter.escaped()) {
                        str = URL.encode(str);
                    }
                    // 拼接，详细说明参见 `Parameter#append()` 方法的说明。
                    if (parameter != null && parameter.append()) {
                        // default. 里获取，适用于 ServiceConfig =》ProviderConfig 、ReferenceConfig =》ConsumerConfig 。
                        String pre = (String) parameters.get(Constants.DEFAULT_KEY + "." + key);
                        if (pre != null && pre.length() > 0) {
                            str = pre + "," + str;
                        }
                        // 通过 `parameters` 属性配置，例如 `AbstractMethodConfig.parameters` 。
                        pre = (String) parameters.get(key);
                        if (pre != null && pre.length() > 0) {
                            str = pre + "," + str;
                        }
                    }
                    if (prefix != null && prefix.length() > 0) {
                        key = prefix + "." + key;
                    }
                    // 添加配置项到 parameters
                    parameters.put(key, str);
                } else if (parameter != null && parameter.required()) {
                    // 当 `@Parameter.required = true` 时，校验配置项非空。
                    throw new IllegalStateException(config.getClass().getSimpleName() + "." + key + " == null");
                }
                // 当方法为 #getParameters() 时
            } else if ("getParameters".equals(name)
                    && Modifier.isPublic(method.getModifiers())
                    && method.getParameterTypes().length == 0
                    && method.getReturnType() == Map.class) { // `#getParameters()` 方法
                // 通过反射，获得 #getParameters() 的返回值为 map 。
                Map<String, String> map = (Map<String, String>) method.invoke(config, new Object[0]);
                // 将 map 添加到 parameters ，kv 格式为 prefix:entry.key entry.value 。
                if (map != null && map.size() > 0) {
                    String pre = (prefix != null && prefix.length() > 0 ? prefix + "." : "");
                    for (Map.Entry<String, String> entry : map.entrySet()) {
                        parameters.put(pre + entry.getKey().replace('-', '.'), entry.getValue());
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
```

这个方法主要是将 Object 里的方法遍历，获取get方法并且该方法带有@Parameter注解，根绝getXXX方法得到XXX属性为key，然后通过反射调用get方法得到该属性值value，再插入到参数map中，


## @Parameter

Parameter 参数注解，用于 Dubbo URL 的 parameters 拼接。

```java
/**
 * Parameter
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Parameter {

    /**
     * 键（别名）
     */
    String key() default "";

    /**
     * 是否必填
     */
    boolean required() default false;

    /**
     * 是否忽略
     */
    boolean excluded() default false;

    /**
     * 是否转义
     */
    boolean escaped() default false;

    /**
     * 是否为属性
     *
     * 目前用于《事件通知》http://dubbo.apache.org/zh-cn/docs/user/demos/events-notify.html
     */
    boolean attribute() default false;

    /**
     * 是否拼接默认属性，参见 {@link com.alibaba.dubbo.config.AbstractConfig#appendParameters(Map, Object, String)} 方法。
     *
     * 我们来看看 `#append() = true` 的属性，有如下四个：
     *   + {@link AbstractInterfaceConfig#getFilter()}
     *   + {@link AbstractInterfaceConfig#getListener()}
     *   + {@link AbstractReferenceConfig#getFilter()}
     *   + {@link AbstractReferenceConfig#getListener()}
     *   + {@link AbstractServiceConfig#getFilter()}
     *   + {@link AbstractServiceConfig#getListener()}
     * 那么，以 AbstractServiceConfig 举例子。
     *
     * 我们知道 ProviderConfig 和 ServiceConfig 继承 AbstractServiceConfig 类，那么 `filter` , `listener` 对应的相同的键。
     * 下面我们以 `filter` 举例子。
     *
     * 在 ServiceConfig 中，默认会<b>继承</b> ProviderConfig 配置的 `filter` 和 `listener` 。
     * 所以这个属性，就是用于，像 ServiceConfig 的这种情况，从 ProviderConfig 读取父属性。
     *
     * 举个例子，如果 `ProviderConfig.filter=aaaFilter` ，`ServiceConfig.filter=bbbFilter` ，最终暴露到 Dubbo URL 时，参数为 `service.filter=aaaFilter,bbbFilter` 。
     */
    boolean append() default false;

}
```

## URL

URL 就是简单的 Java Bean 

```java
public final class URL implements Serializable {

    /**
     * 协议名
     */
    private final String protocol;
    /**
     * 用户名
     */
    private final String username;
    /**
     * 密码
     */
    private final String password;
    /**
     * by default, host to registry
     * 地址
     */
    private final String host;
    /**
     * by default, port to registry
     * 端口
     */
    private final int port;
    /**
     * 路径（服务名）
     */
    private final String path;
    /**
     * 参数集合
     */
    private final Map<String, String> parameters;
    
    // ... 省略其他代码
    
}
```

上文我们提到所有配置最终都将转换为 Dubbo URL 表示，并由服务提供方生成，经注册中心传递给消费方，各属性对应 URL 的参数，参见配置项一览表中的 “对应URL参数” 列。那么一个 Service 注册到注册中心的格式如下

```
dubbo://192.168.3.17:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&default.delay=-1&default.retries=0&default.service.filter=demoFilter&delay=-1&dubbo=2.0.0&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=19031&side=provider&timestamp=1519651641799
```

格式为 protocol://username:password@host:port/path?key=value&key=value ，通过 URL#buildString(...) 方法生成。

parameters 属性，参数集合。从上面的 Service URL 例子我们可以看到，里面的 key=value ，实际上就是 Service 对应的配置项。该属性，通过 AbstractConfig#appendParameters(parameters, config, prefix) 方法生成。