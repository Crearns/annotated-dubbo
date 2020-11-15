# Dubbo 服务引用源码分析总结(一)本地引用

Dubbo 服务引用，和 Dubbo 服务暴露一样，也有两种方式：

```xml
// 本地引用，JVM 本地调用。
<dubbo:service scope="local" />

// 远程暴露，网络远程通信。
<dubbo:service scope="remote" />
```

# createProxy

<a href="https://sm.ms/image/eiuMN5XqL1ksHR8" target="_blank"><img src="https://i.loli.net/2020/09/06/eiuMN5XqL1ksHR8.jpg" ></a>


```java
private T createProxy(Map<String, String> map) {
    URL tmpUrl = new URL("temp", "localhost", 0, map);
    // 是否本地引用
    final boolean isJvmRefer;
    // injvm 属性为空，不通过该属性判断
    if (isInjvm() == null) {
        // 直连服务提供者，参见文档《直连提供者》http://dubbo.apache.org/zh-cn/docs/user/demos/explicit-target.html
        if (url != null && url.length() > 0) { // if a url is specified, don't do local reference
            isJvmRefer = false;
        } else if (InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl)) {
            // 通过 `tmpUrl` 判断，是否需要本地引用
            // by default, reference local service if there is
            isJvmRefer = true;
        } else {
            // 默认不是
            isJvmRefer = false;
        }
    } else {
        // 通过 injvm 属性。
        isJvmRefer = isInjvm().booleanValue();
    }

    // 本地引用
    if (isJvmRefer) {
        // 创建服务引用 URL 对象
        URL url = new URL(Constants.LOCAL_PROTOCOL, NetUtils.LOCALHOST, 0, interfaceClass.getName()).addParameters(map);
        // 引用服务，返回 Invoker 对象
        invoker = refprotocol.refer(interfaceClass, url);
        if (logger.isInfoEnabled()) {
            logger.info("Using injvm service " + interfaceClass.getName());
        }
        // 正常流程，一般为远程引用
    } else {
        // 省略
    }

    // 启动时检查
    Boolean c = check;
    if (c == null && consumer != null) {
        c = consumer.isCheck();
    }
    if (c == null) {
        c = true; // default true
    }
    if (c && !invoker.isAvailable()) {
        throw new IllegalStateException("Failed to check the status of the service " + interfaceName + ". No provider available for the service " + (group == null ? "" : group + "/") + interfaceName + (version == null ? "" : ":" + version) + " from the url " + invoker.getUrl() + " to the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
    }
    if (logger.isInfoEnabled()) {
        logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
    }
    // 创建 Service 代理对象
    // create service proxy
    return (T) proxyFactory.getProxy(invoker);
}


public boolean isInjvmRefer(URL url) {
    final boolean isJvmRefer;
    String scope = url.getParameter(Constants.SCOPE_KEY);
    // Since injvm protocol is configured explicitly, we don't need to set any extra flag, use normal refer process.
    if (Constants.LOCAL_PROTOCOL.toString().equals(url.getProtocol())) {
        isJvmRefer = false;
        // 当 `scope = local` 或者 `injvm = true` 时，本地引用
    } else if (Constants.SCOPE_LOCAL.equals(scope) || (url.getParameter("injvm", false))) {
        // if it's declared as local reference
        // 'scope=local' is equivalent to 'injvm=true', injvm will be deprecated in the future release
        isJvmRefer = true;
        // 当 `scope = remote` 时，远程引用
    } else if (Constants.SCOPE_REMOTE.equals(scope)) {
        // it's declared as remote reference
        isJvmRefer = false;
        // 当 `generic = true` 时，即使用泛化调用，远程引用。
    } else if (url.getParameter(Constants.GENERIC_KEY, false)) {
        // generic invocation is not local reference
        isJvmRefer = false;
        // 当本地已经有该 Exporter 时，本地引用
    } else if (getExporter(exporterMap, url) != null) {
        // by default, go through local reference if there's the service exposed locally
        isJvmRefer = true;
    } else {
        // 默认，远程引用
        isJvmRefer = false;
    }
    return isJvmRefer;
}
```


创建本地服务引用 URL 对象。

调用 Protocol#refer(interface, url) 方法，引用服务，返回 Invoker 对象。
此处 Dubbo SPI 自适应的特性的好处就出来了，可以自动根据 URL 参数，获得对应的拓展实现。例如，invoker 传入后，根据 invoker.url 自动获得对应 Protocol 拓展实现为 InjvmProtocol 。
实际上，Protocol 有两个 Wrapper 拓展实现类： ProtocolFilterWrapper、ProtocolListenerWrapper 。所以，#refer(...) 方法的调用顺序是：Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => InjvmProtocol 。

# Protocol

## ProtocolFilterWrapper
### refer
```java
public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
    // 注册中心
    if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
        return protocol.refer(type, url);
    }
    // 引用服务，返回 Invoker 对象
    // 给改 Invoker 对象，包装成带有 Filter 过滤链的 Invoker 对象
    return buildInvokerChain(protocol.refer(type, url), Constants.REFERENCE_FILTER_KEY, Constants.CONSUMER);
}
```

### buildInvokerChain

本地引用不需要注册中心，所以会直接调用buildInvokerChain方法，方法逻辑和服务暴露类似，服务引用的的filter如下：

* ConsumerContextFilter
* FutureFilter
* MonitorFilter

到 filter 会具体分析

## ProtocolListenerWrapper
### refer
```java
public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
    // 注册中心协议
    if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
        return protocol.refer(type, url);
    }

    // 创建 ListenerInvokerWrapper 对象
    return new ListenerInvokerWrapper<T>(protocol.refer(type, url),// 引用服务
            Collections.unmodifiableList(
                    ExtensionLoader.getExtensionLoader(InvokerListener.class)// 获得 InvokerListener 数组
                            .getActivateExtension(url, Constants.INVOKER_LISTENER_KEY)));
}
```

## InjvmProtocol
### refer
```java
public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
    // 创建 InjvmInvoker 对象。注意，传入的 exporterMap 参数，包含所有的 InjvmExporter 对象。
    return new InjvmInvoker<T>(serviceType, url, url.getServiceKey(), exporterMap);
}
```


# Invoker

## AbstractInvoker
```java
/**
 * 接口类型
 */
private final Class<T> type;
/**
 * 服务 URL
 */
private final URL url;
/**
 * 公用的隐式传参。在 {@link #invoke(Invocation)} 方法中使用。
 */
private final Map<String, String> attachment;
/**
 * 是否可用
 */
private volatile boolean available = true;
/**
 * 是否销毁
 */
private AtomicBoolean destroyed = new AtomicBoolean(false);

```
## InjvmInvoker
```java
/**
 * 服务键
 */
private final String key;
/**
 * Exporter 集合
 *
 * key: 服务键
 *
 * 该值实际就是 {@link com.alibaba.dubbo.rpc.protocol.AbstractProtocol#exporterMap}
 */
private final Map<String, Exporter<?>> exporterMap;

InjvmInvoker(Class<T> type, URL url, String key, Map<String, Exporter<?>> exporterMap) {
    super(type, url);
    this.key = key;
    this.exporterMap = exporterMap;
}
```

### isAvailabe
```java
@Override
public boolean isAvailable() {
    // 判断是否有 Exporter 对象
    InjvmExporter<?> exporter = (InjvmExporter<?>) exporterMap.get(key);
    if (exporter == null) {
        return false;
    } else {
        return super.isAvailable();
    }
}
```

开启 启动时检查 时，调用该方法，判断该 Invoker 对象，是否有对应的 Exporter 。若不存在，说明依赖服务不存在，检查不通过

## ListenerInvokerWrapper
实现 Invoker 接口，具有监听器功能的 Invoker 包装器。

```java
public class ListenerInvokerWrapper<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(ListenerInvokerWrapper.class);

    /**
     * 真实的 Invoker 对象
     */
    private final Invoker<T> invoker;
    /**
     * Invoker 监听器数组
     */
    private final List<InvokerListener> listeners;

    public ListenerInvokerWrapper(Invoker<T> invoker, List<InvokerListener> listeners) {
        if (invoker == null) {
            throw new IllegalArgumentException("invoker == null");
        }
        this.invoker = invoker;
        this.listeners = listeners;
        // 执行监听器
        if (listeners != null && !listeners.isEmpty()) {
            for (InvokerListener listener : listeners) {
                if (listener != null) {
                    try {
                        listener.referred(invoker);
                    } catch (Throwable t) {
                        logger.error(t.getMessage(), t);
                    }
                }
            }
        }
    }

    public Class<T> getInterface() {
        return invoker.getInterface();
    }

    public URL getUrl() {
        return invoker.getUrl();
    }

    public boolean isAvailable() {
        return invoker.isAvailable();
    }

    public Result invoke(Invocation invocation) throws RpcException {
        return invoker.invoke(invocation);
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + (getUrl() == null ? " " : getUrl().toString());
    }

    public void destroy() {
        try {
            invoker.destroy();
        } finally {
            // 执行监听器
            if (listeners != null && !listeners.isEmpty()) {
                for (InvokerListener listener : listeners) {
                    if (listener != null) {
                        try {
                            listener.destroyed(invoker);
                        } catch (Throwable t) {
                            logger.error(t.getMessage(), t);
                        }
                    }
                }
            }
        }
    }

}
```

和 ListenerExporterWrapper 有点区别是 ListenerExporterWrapper 如果捕获异常仅打印日志，继续进行，并不会抛出。

# InvokerListener

```java
@SPI
public interface InvokerListener {

    /**
     * The invoker referred
     *
     * 当服务引用完成
     *
     * @param invoker
     * @throws RpcException
     * @see com.alibaba.dubbo.rpc.Protocol#refer(Class, URL)
     */
    void referred(Invoker<?> invoker) throws RpcException;

    /**
     * The invoker destroyed.
     *
     * 当服务销毁引用完成
     *
     * @param invoker
     * @see com.alibaba.dubbo.rpc.Invoker#destroy()
     */
    void destroyed(Invoker<?> invoker);

}
```
