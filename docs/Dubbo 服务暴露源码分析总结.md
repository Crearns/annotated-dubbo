# Dubbo 服务暴露源码分析总结

在 Dubbo 中，服务暴露分为本地暴露以及远程暴露，通过配置区分。

```xml
<dubbo:service scope="local" />

<dubbo:service scope="remote" />

<dubbo:service scope="none" />
```

在不配置 scope 的情况下，默认两种方式都暴露。因为，Dubbo 自身无法确认应用中，是否存在本地引用的情况。
大多数情况下，我们不需要配置 scope 。

# 本地暴露

## doExportUrls


<a href="https://sm.ms/image/d5MueLtQqTfvjED" target="_blank"><img src="https://i.loli.net/2020/09/05/d5MueLtQqTfvjED.jpg" ></a>


```java
/**
* 暴露 Dubbo URL
*/

protected List<ProtocolConfig> protocols;

@SuppressWarnings({"unchecked", "rawtypes"})
private void doExportUrls() {
    // 加载注册中心 URL 数组
    List<URL> registryURLs = loadRegistries(true);
    // 循环 `protocols` ，向逐个注册中心分组暴露服务。
    for (ProtocolConfig protocolConfig : protocols) {
        doExportUrlsFor1Protocol(protocolConfig, registryURLs);
    }
}
```

本地暴露不会往注册中心中注册服务，因为本地调用只是JVM内部调用。


## loadRegistries

loadRegistries 获得注册中心的URL，而注册中心的URL只有远程暴露的时候会用到。

```java
/**
* 加载注册中心 URL 数组
* @param provider 是否是服务提供者
* @return URL 数组
*/
protected List<URL> loadRegistries(boolean provider) {
    // 校验 RegistryConfig 配置数组。
    checkRegistry();
    // 创建 注册中心 URL 数组
    List<URL> registryList = new ArrayList<URL>();
    if (registries != null && !registries.isEmpty()) {
        for (RegistryConfig config : registries) {
            // 获得注册中心的地址
            String address = config.getAddress();
            if (address == null || address.length() == 0) {
                address = Constants.ANYHOST_VALUE;
            }
            // 从启动参数读取
            String sysaddress = System.getProperty("dubbo.registry.address");
            if (sysaddress != null && sysaddress.length() > 0) {
                address = sysaddress;
            }
            // 有效的地址
            if (address != null && address.length() > 0
                    && !RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(address)) {
                Map<String, String> map = new HashMap<String, String>();
                // 将各种配置对象，添加到 `map` 集合中。
                appendParameters(map, application);
                appendParameters(map, config);
                // 添加 `path` `dubbo` `timestamp` `pid` 到 `map` 集合中。
                map.put("path", RegistryService.class.getName());
                map.put("dubbo", Version.getVersion());
                map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
                if (ConfigUtils.getPid() > 0) {
                    map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
                }
                // 若不存在 `protocol` 参数，默认 "dubbo" 添加到 `map` 集合中。
                if (!map.containsKey("protocol")) {
                    if (ExtensionLoader.getExtensionLoader(RegistryFactory.class).hasExtension("remote")) {
                        map.put("protocol", "remote");
                    } else {
                        map.put("protocol", "dubbo");
                    }
                }
                // 解析地址，创建 Dubbo URL 数组。（数组大小可以为一）
                List<URL> urls = UrlUtils.parseURLs(address, map);
                // 循环 `url` ，设置 "registry" 和 "protocol" 属性。
                for (URL url : urls) {
                    // 设置 `registry=${protocol}` 和 `protocol=registry` 到 URL
                    url = url.addParameter(Constants.REGISTRY_KEY, url.getProtocol());
                    url = url.setProtocol(Constants.REGISTRY_PROTOCOL);
                    // 添加到结果
                    // 若是服务提供者，判断是否只订阅不注册。如果是，不添加结果到 registryList 中
                    // 若是服务消费者，判断是否只注册不订阅。如果是，不添加到结果 registryList 。
                    if ((provider && url.getParameter(Constants.REGISTER_KEY, true))
                            || (!provider && url.getParameter(Constants.SUBSCRIBE_KEY, true))) {
                        registryList.add(url);
                    }
                }
            }
        }
    }
    return registryList;
}


/**
* 校验 RegistryConfig 配置数组。
* 实际上，该方法会初始化 RegistryConfig 的配置属性。
*/
protected void checkRegistry() {
    // 当 RegistryConfig 对象数组为空时，若有 `dubbo.registry.address` 配置，进行创建。
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
    // 读取环境变量和 properties 配置到 RegistryConfig 对象数组。
    for (RegistryConfig registryConfig : registries) {
        appendProperties(registryConfig);
    }
}
```

其实这个方法就做了4件事

1. 从配置中获得注册中心的地址，并且封装成RegistryConfig，添加到registries
2. 遍历registries，将 path、dubbo、timestamp、pid、protocol等参数添加到map中
3. 将map以及遍历的地址解析成URL对象，并且添加registry协议头和registry参数
4. 添加到一个List中返回。

例子：
在配置中添加以下配置
```xml
<dubbo:registry address="zookeeper://127.0.0.1:2181"/>
```
最后会解析成

```registry://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.0&pid=47529&qos.port=22222&registry=zookeeper&timestamp=1599275053298```



## doExportUrlsFor1Protocol
```java
/**
* 基于单个协议 暴露服务
* @param protocolConfig 协议配置对象
* @param registryURLs 注册中心链接对象数组
*/
private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
    // 省略 见 ServiceConfig 源码分析
    // don't export when none is configured
    if (!Constants.SCOPE_NONE.toString().equalsIgnoreCase(scope)) {

        // export to local if the config is not remote (export to remote only when config is remote)
        if (!Constants.SCOPE_REMOTE.toString().equalsIgnoreCase(scope)) {
            exportLocal(url);
        }
        // export to remote if the config is not local (export to local only when config is local)
        if (!Constants.SCOPE_LOCAL.toString().equalsIgnoreCase(scope)) {
            // 省略 远程暴露
        }
    }
    this.urls.add(url);
}

```

### exportLocal
```java
private void exportLocal(URL url) {
    if (!Constants.LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
        // 创建本地 Dubbo URL
        URL local = URL.valueOf(url.toFullString())
                .setProtocol(Constants.LOCAL_PROTOCOL) // injvm
                .setHost(LOCALHOST) // 本地
                .setPort(0); // 端口=0
        // 添加服务的真实类名，例如 DemoServiceImpl ，仅用于 RestProtocol 中。
        ServiceClassHolder.getInstance().pushServiceClass(getServiceClass(ref));
        // 使用 ProxyFactory 创建 Invoker 对象
        // 使用 Protocol 暴露 Invoker 对象
        // 此处 Dubbo SPI 自适应的特性的好处就出来了，可以自动根据 URL 参数，获得对应的拓展实现
        // 例如，invoker 传入后，根据 invoker.url 自动获得对应 Protocol 拓展实现为 InjvmProtocol 。
        // 实际上，Protocol 有两个 Wrapper 拓展实现类： ProtocolFilterWrapper、ProtocolListenerWrapper
        //所以，#export(...) 方法的调用顺序是：
        // Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => InjvmProtocol 。
        Exporter<?> exporter = protocol.export(
                proxyFactory.getInvoker(ref, (Class) interfaceClass, local));
        exporters.add(exporter);
        logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry");
    }
}

```


### ProtocolFilterWrapper
ProtocolFilterWrapper 实现 Protocol 接口，Protocol 的 Wrapper 拓展实现类，用于给 Invoker 增加过滤链。

```java
public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
    // 注册中心 本地暴露服务不会符合这个判断。在远程暴露服务会符合暴露该判断
    if (Constants.REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
        return protocol.export(invoker);
    }
    // 建立带有 Filter 过滤链的 Invoker ，再暴露服务。
    return protocol.export(buildInvokerChain(invoker, Constants.SERVICE_FILTER_KEY, Constants.PROVIDER));
}


/**
* 创建带 Filter 链的 Invoker 对象
* @param invoker Invoker 对象
* @param key 获取 URL 参数名
* @param group group 分组
* @param <T> <T> 泛型
* @return Invoker 对象
*/
private static <T> Invoker<T> buildInvokerChain(final Invoker<T> invoker, String key, String group) {
    Invoker<T> last = invoker;
    // 获得过滤器数组
    List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class).getActivateExtension(invoker.getUrl(), key, group);
    // 倒序循环 Filter ，创建带 Filter 链的 Invoker 对象
    if (!filters.isEmpty()) {
        for (int i = filters.size() - 1; i >= 0; i--) {
            // todo 这块逻辑还得看看
            final Filter filter = filters.get(i);
            final Invoker<T> next = last;
            last = new Invoker<T>() {

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
                    return filter.invoke(next, invocation);
                }

                public void destroy() {
                    invoker.destroy();
                }

                @Override
                public String toString() {
                    return invoker.toString();
                }
            };
        }
    }
    return last;
}
```

### ProtocolListenerWrapper
com.alibaba.dubbo.rpc.protocol.ProtocolListenerWrapper ，实现 Protocol 接口，Protocol 的 Wrapper 拓展实现类，用于给 Exporter 增加 ExporterListener ，监听 Exporter 暴露完成和取消暴露完成。

```java
public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
    // 注册中心
    if (Constants.REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
        return protocol.export(invoker);
    }
    // 创建带 ExporterListener 的 Exporter 对象
    return new ListenerExporterWrapper<T>(protocol.export(invoker), // 暴露服务，创建 Exporter 对象
            // 获得 ExporterListener 数组
            Collections.unmodifiableList(ExtensionLoader.getExtensionLoader(ExporterListener.class)
                    .getActivateExtension(invoker.getUrl(), Constants.EXPORTER_LISTENER_KEY)));
}
```


## AbstractProtocol

```java
/**
    * exporterMap 属性，Exporter 集合。该集合拥有该协议中，所有暴露中的 Exporter 对象。其中 key 为服务键。不同协议的实现，生成的方式略有差距。例如：
    * InjvmProtocol 使用 URL#getServiceKey() 方法
    * DubboProtocol 使用 #serviceKey(URL) 方法。
    * 差别主要在于是否包含 port 。实际上，也是一致的。因为 InjvmProtocol 统一 port=0 。
    */
protected final Map<String, Exporter<?>> exporterMap = new ConcurrentHashMap<String, Exporter<?>>();
```

### InjvmProtocol
```java
/**
    * 协议名
    */
public static final String NAME = Constants.LOCAL_PROTOCOL;

/**
    * 默认端口
    */
public static final int DEFAULT_PORT = 0;

/**
    * 单例。在 Dubbo SPI 中，被初始化，有且仅有一次。
    */
private static InjvmProtocol INSTANCE;
```

### export

```java
public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
    return new InjvmExporter<T>(invoker, invoker.getUrl().getServiceKey(), exporterMap);
}
```


# Exporter
## AbstractExporter

```java
/**
 * AbstractExporter.
 */
public abstract class AbstractExporter<T> implements Exporter<T> {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private final Invoker<T> invoker;

    private volatile boolean unexported = false;

    public AbstractExporter(Invoker<T> invoker) {
        if (invoker == null)
            throw new IllegalStateException("service invoker == null");
        if (invoker.getInterface() == null)
            throw new IllegalStateException("service type == null");
        if (invoker.getUrl() == null)
            throw new IllegalStateException("service url == null");
        this.invoker = invoker;
    }

    public Invoker<T> getInvoker() {
        return invoker;
    }

    public void unexport() {
        if (unexported) {
            return;
        }
        unexported = true;
        getInvoker().destroy();
    }

    public String toString() {
        return getInvoker().toString();
    }

}
```

## InjvmExporter
实现 Exporter 接口，具有监听器功能的 Exporter 包装器。
```java
/**
 * InjvmExporter
 */
class InjvmExporter<T> extends AbstractExporter<T> {

    private final String key;

    private final Map<String, Exporter<?>> exporterMap;

    InjvmExporter(Invoker<T> invoker, String key, Map<String, Exporter<?>> exporterMap) {
        super(invoker);
        this.key = key;
        this.exporterMap = exporterMap;
        // 添加到 Exporter 集合
        exporterMap.put(key, this);
    }

    public void unexport() {
        super.unexport();
        // 移除出 Exporter 集合
        exporterMap.remove(key);
    }

}
```

## ListenerExporterWrapper


```java
/**
 * ListenerExporter
 */
public class ListenerExporterWrapper<T> implements Exporter<T> {

    private static final Logger logger = LoggerFactory.getLogger(ListenerExporterWrapper.class);

    /**
     * 真实的 Exporter 对象
     */
    private final Exporter<T> exporter;

    /**
     * Exporter 监听器数组
     */
    private final List<ExporterListener> listeners;

    public ListenerExporterWrapper(Exporter<T> exporter, List<ExporterListener> listeners) {
        if (exporter == null) {
            throw new IllegalArgumentException("exporter == null");
        }
        this.exporter = exporter;
        this.listeners = listeners;
        // 执行监听器
        if (listeners != null && !listeners.isEmpty()) {
            RuntimeException exception = null;
            for (ExporterListener listener : listeners) {
                if (listener != null) {
                    try {
                        listener.exported(this);
                    } catch (RuntimeException t) {
                        logger.error(t.getMessage(), t);
                        exception = t;
                    }
                }
            }
            if (exception != null) {
                throw exception;
            }
        }
    }

    public Invoker<T> getInvoker() {
        return exporter.getInvoker();
    }

    public void unexport() {
        try {
            exporter.unexport();
        } finally {
            // 执行监听器
            if (listeners != null && !listeners.isEmpty()) {
                RuntimeException exception = null;
                for (ExporterListener listener : listeners) {
                    if (listener != null) {
                        try {
                            listener.unexported(this);
                        } catch (RuntimeException t) {
                            logger.error(t.getMessage(), t);
                            exception = t;
                        }
                    }
                }
                if (exception != null) {
                    throw exception;
                }
            }
        }
    }


```



# 远程暴露

远程暴露服务的顺序图如下

<a href="https://sm.ms/image/kTlEHe5vpGcVCDi" target="_blank"><img src="https://i.loli.net/2020/09/05/kTlEHe5vpGcVCDi.jpg" ></a>

下面分析 #doExportUrlsFor1Protocol(protocolConfig, registryURLs) 方法中省略的部分

```java
// export to remote if the config is not local (export to local only when config is local)
if (!Constants.SCOPE_LOCAL.toString().equalsIgnoreCase(scope)) {
    if (logger.isInfoEnabled()) {
        logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
    }
    if (registryURLs != null && !registryURLs.isEmpty()) {
        // 循环祖册中心 URL 数组 registryURLs 
        for (URL registryURL : registryURLs) {
            // "dynamic" ：服务是否动态注册，如果设为false，注册后将显示后disable状态，需人工启用，并且服务提供者停止时，也不会自动取消注册，需人工禁用。
            url = url.addParameterIfAbsent("dynamic", registryURL.getParameter("dynamic"));
            // 获得监控中心 URL
            URL monitorUrl = loadMonitor(registryURL);
            if (monitorUrl != null) {
                url = url.addParameterAndEncoded(Constants.MONITOR_KEY, monitorUrl.toFullString());
            }
            if (logger.isInfoEnabled()) {
                logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
            }

            // 使用 ProxyFactory 创建 Invoker 对象
            Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(Constants.EXPORT_KEY, url.toFullString()));

            // 创建 DelegateProviderMetaDataInvoker 对象
            DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

            // 使用 Protocol 暴露 Invoker 对象
            Exporter<?> exporter = protocol.export(wrapperInvoker);

            // 添加到 `exporters`
            exporters.add(exporter);
        }
    } else { // 用于被服务消费者直连服务提供者，参见文档 http://dubbo.apache.org/zh-cn/docs/user/demos/explicit-target.html 。主要用于开发测试环境使用。
        Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

        Exporter<?> exporter = protocol.export(wrapperInvoker);
        exporters.add(exporter);
    }
}
```

实际上，Protocol 有两个 Wrapper 拓展实现类： ProtocolFilterWrapper、ProtocolListenerWrapper 。所以，#export(...) 方法的调用顺序是：

Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => RegistryProtocol
=></br>
Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => DubboProtocol</br>
也就是说，这一条大的调用链，包含两条小的调用链。原因是：
* 首先，传入的是注册中心的 URL ，通过 Protocol$Adaptive 获取到的是 RegistryProtocol 对象。
* 其次，RegistryProtocol 会在其 #export(...) 方法中，使用服务提供者的 URL ( 即注册中心的 URL 的 export 参数值)，再次调用 Protocol$Adaptive 获取到的是 DubboProtocol 对象，进行服务暴露。

为什么是这样的顺序？通过这样的顺序，可以实现类似 AOP 的效果，在本地服务器启动完成后，再向注册中心注册。伪代码如下：
```java
RegistryProtocol#export(...) {
    
    // 1. 启动本地服务器、暴露真正的服务
    DubboProtocol#export(...);
    
    // 2. 向注册中心注册。 
}
```

方法参数传入的是注册中心的URL、其中注册中心的URL带有服务的URL，在本地暴露的时候后将服务URL剥离开来，进行DubboProtocol暴露，并启动服务器。启动后再进行注册中心的注册。


相比本地暴露，远程暴露会多做如下几件事情：

* 启动通信服务器，绑定服务端口，提供远程调用。
* 向注册中心注册服务提供者，提供服务消费者从注册中心发现服务。


## loadMonitor
看看就好
```java
protected URL loadMonitor(URL registryURL) {
    // 从 属性配置 中加载配置到 MonitorConfig 对象。
    if (monitor == null) {
        String monitorAddress = ConfigUtils.getProperty("dubbo.monitor.address");
        String monitorProtocol = ConfigUtils.getProperty("dubbo.monitor.protocol");
        if ((monitorAddress == null || monitorAddress.length() == 0) && (monitorProtocol == null || monitorProtocol.length() == 0)) {
            return null;
        }

        monitor = new MonitorConfig();
        if (monitorAddress != null && monitorAddress.length() > 0) {
            monitor.setAddress(monitorAddress);
        }
        if (monitorProtocol != null && monitorProtocol.length() > 0) {
            monitor.setProtocol(monitorProtocol);
        }
    }
    appendProperties(monitor);
    // 添加 `interface` `dubbo` `timestamp` `pid` 到 `map` 集合中
    Map<String, String> map = new HashMap<String, String>();
    map.put(Constants.INTERFACE_KEY, MonitorService.class.getName());
    map.put("dubbo", Version.getVersion());
    map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
    if (ConfigUtils.getPid() > 0) {
        map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
    }
    // 将 MonitorConfig ，添加到 `map` 集合中。
    appendParameters(map, monitor);
    // 获得地址
    String address = monitor.getAddress();
    String sysaddress = System.getProperty("dubbo.monitor.address");
    if (sysaddress != null && sysaddress.length() > 0) {
        address = sysaddress;
    }
    // 直连监控中心服务器地址
    if (ConfigUtils.isNotEmpty(address)) {
        if (!map.containsKey(Constants.PROTOCOL_KEY)) {
            if (ExtensionLoader.getExtensionLoader(MonitorFactory.class).hasExtension("logstat")) {
                map.put(Constants.PROTOCOL_KEY, "logstat");
            } else {
                map.put(Constants.PROTOCOL_KEY, "dubbo");
            }
        }
        // 解析地址，创建 Dubbo URL 对象。
        return UrlUtils.parseURL(address, map);
        // 从注册中心发现监控中心地址
    } else if (Constants.REGISTRY_PROTOCOL.equals(monitor.getProtocol()) && registryURL != null) {
        return registryURL.setProtocol("dubbo").addParameter(Constants.PROTOCOL_KEY, "registry").addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map));
    }
    return null;
}
```

## Protocol
### ProtocolFilterWrapper
```java
public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
    // 注册中心
    if (Constants.REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
        return protocol.export(invoker);
    }
    // 建立带有 Filter 过滤链的 Invoker ，再暴露服务。
    return protocol.export(buildInvokerChain(invoker, Constants.SERVICE_FILTER_KEY, Constants.PROVIDER));
}
```

#### RegistryProtocol
```java
/**
    * 单例。在 Dubbo SPI 中，被初始化，有且仅有一次。
    */
private static RegistryProtocol INSTANCE;
private final Map<URL, NotifyListener> overrideListeners = new ConcurrentHashMap<URL, NotifyListener>();
/**
    * 绑定关系集合。
    *
    * key：服务 Dubbo URL
    */
// To solve the problem of RMI repeated exposure port conflicts, the services that have been exposed are no longer exposed.
// 用于解决rmi重复暴露端口冲突的问题，已经暴露过的服务不再重新暴露
// providerurl <--> exporter
private final Map<String, ExporterChangeableWrapper<?>> bounds = new ConcurrentHashMap<String, ExporterChangeableWrapper<?>>();
private Cluster cluster;
/**
    * Protocol 自适应拓展实现类，通过 Dubbo SPI 自动注入。
    */
private Protocol protocol;
/**
    * RegistryFactory 自适应拓展实现类，通过 Dubbo SPI 自动注入。
    */
private RegistryFactory registryFactory;
private ProxyFactory proxyFactory;
```

#### export
```java
public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
    // 暴露服务
    //export invoker
    final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker);

    // 获得注册中心 URL
    URL registryUrl = getRegistryUrl(originInvoker);

    // 获得注册中心对象
    //registry provider
    final Registry registry = getRegistry(originInvoker);

    // 获得服务提供者 URL
    final URL registedProviderUrl = getRegistedProviderUrl(originInvoker);

    //to judge to delay publish whether or not
    boolean register = registedProviderUrl.getParameter("register", true);

    // 向本地注册表，注册服务提供者
    ProviderConsumerRegTable.registerProvider(originInvoker, registryUrl, registedProviderUrl);

    // 向注册中心注册服务提供者（自己）
    if (register) {
        register(registryUrl, registedProviderUrl);
        ProviderConsumerRegTable.getProviderWrapper(originInvoker).setReg(true);
    }

    // 使用 OverrideListener 对象，订阅配置规则
    // Subscribe the override data
    // FIXME When the provider subscribes, it will affect the scene : a certain JVM exposes the service and call the same service. Because the subscribed is cached key with the name of the service, it causes the subscription information to cover.
    final URL overrideSubscribeUrl = getSubscribedOverrideUrl(registedProviderUrl);
    final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);
    overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);
    registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);
    //Ensure that a new exporter instance is returned every time export
    return new DestroyableExporter<T>(exporter, originInvoker, overrideSubscribeUrl, registedProviderUrl);
}


//=============================
@SuppressWarnings("unchecked")
private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker) {
    // 获得在 `bounds` 中的缓存 Key
    String key = getCacheKey(originInvoker);
    // 从 `bounds` 获得，是不是已经暴露过服务
    ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
    if (exporter == null) {
        synchronized (bounds) {
            exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
            // 未暴露过，进行暴露服务 double check
            if (exporter == null) {
                // 创建 Invoker Delegate 对象
                final Invoker<?> invokerDelegete = new InvokerDelegete<T>(originInvoker, getProviderUrl(originInvoker));
                // 暴露服务，创建 Exporter 对象
                // 使用 创建的Exporter对象 + originInvoker ，创建 ExporterChangeableWrapper 对象
                exporter = new ExporterChangeableWrapper<T>((Exporter<T>) protocol.export(invokerDelegete), originInvoker);
                // 添加到 `bounds`
                bounds.put(key, exporter);
            }
        }
    }
    return exporter;
}


//=============================
private URL getRegistryUrl(Invoker<?> originInvoker) {
    URL registryUrl = originInvoker.getUrl();
    if (Constants.REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {
        String protocol = registryUrl.getParameter(Constants.REGISTRY_KEY, Constants.DEFAULT_DIRECTORY);
        registryUrl = registryUrl.setProtocol(protocol).removeParameter(Constants.REGISTRY_KEY);
    }
    return registryUrl;
}

```

例子： 在[前面的例子中](##loadRegistries)会将url解析成

 ```
 registry://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.0&pid=47529&qos.port=22222&registry=zookeeper&timestamp=1599275053298
 ```

这个方法会将这个字符串又解析回

```
zookeeper://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.0&export=dubbo%3A%2F%2F192.168.1.101%3A20880%2Fcom.alibaba.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider%26bind.ip%3D192.168.1.101%26bind.port%3D20880%26dubbo%3D2.0.0%26generic%3Dfalse%26interface%3Dcom.alibaba.dubbo.demo.DemoService%26methods%3DsayHello%26pid%3D51541%26qos.port%3D22222%26side%3Dprovider%26timestamp%3D1599297418401&pid=51541&qos.port=22222&timestamp=1599297418384
```

```java
private URL getRegistedProviderUrl(final Invoker<?> originInvoker) {
    // 从注册中心的 export 参数中，获得服务提供者的 URL
    URL providerUrl = getProviderUrl(originInvoker);
    //The address you see at the registry
    final URL registedProviderUrl = providerUrl.removeParameters(getFilteredKeys(providerUrl))// 移除 .hide 为前缀的参数
            .removeParameter(Constants.MONITOR_KEY) // monitor
            .removeParameter(Constants.BIND_IP_KEY) // bind.ip
            .removeParameter(Constants.BIND_PORT_KEY) // bind.port
            .removeParameter(QOS_ENABLE) // qos.enable
            .removeParameter(QOS_PORT)// qos.port
            .removeParameter(ACCEPT_FOREIGN_IP); // qos.accept.foreign.ip
    return registedProviderUrl;
}


private URL getProviderUrl(final Invoker<?> origininvoker) {
    String export = origininvoker.getUrl().getParameterAndDecoded(Constants.EXPORT_KEY);
    if (export == null || export.length() == 0) {
        throw new IllegalArgumentException("The registry export url is null! registry: " + origininvoker.getUrl());
    }

    URL providerUrl = URL.valueOf(export);
    return providerUrl;
}

private static String[] getFilteredKeys(URL url) {
    Map<String, String> params = url.getParameters();
    if (params != null && !params.isEmpty()) {
        List<String> filteredKeys = new ArrayList<String>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry != null && entry.getKey() != null && entry.getKey().startsWith(Constants.HIDE_KEY_PREFIX)) {
                filteredKeys.add(entry.getKey());
            }
        }
        return filteredKeys.toArray(new String[filteredKeys.size()]);
    } else {
        return new String[]{};
    }
}
```

通过getRegistedProviderUrl方法获得提供者url
```
dubbo://192.168.1.101:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&dubbo=2.0.0&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=51654&side=provider&timestamp=1599297903170
```

```java

if (register) {
    register(registryUrl, registedProviderUrl);
    ProviderConsumerRegTable.getProviderWrapper(originInvoker).setReg(true);
}

public void register(URL registryUrl, URL registedProviderUrl) {
    Registry registry = registryFactory.getRegistry(registryUrl);
    registry.register(registedProviderUrl);
}
```

#### doLocalExport
暴露服务。此处的 Local 指的是，本地启动服务，但是不包括向注册中心注册服务的意思。
```java
@SuppressWarnings("unchecked")
private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker) {
    // 获得在 `bounds` 中的缓存 Key
    String key = getCacheKey(originInvoker);
    // 从 `bounds` 获得，是不是已经暴露过服务
    ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
    if (exporter == null) {
        synchronized (bounds) {
            exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
            // 未暴露过，进行暴露服务 double check
            if (exporter == null) {
                // 创建 Invoker Delegate 对象
                final Invoker<?> invokerDelegete = new InvokerDelegete<T>(originInvoker, getProviderUrl(originInvoker));
                // 暴露服务，创建 Exporter 对象
                // 使用 创建的Exporter对象 + originInvoker ，创建 ExporterChangeableWrapper 对象
                exporter = new ExporterChangeableWrapper<T>((Exporter<T>) protocol.export(invokerDelegete), originInvoker);
                // 添加到 `bounds`
                bounds.put(key, exporter);
            }
        }
    }
    return exporter;
}

/**
 * Get the key cached in bounds by invoker
 *
 * 获 取invoker 在 bounds中 缓存的key
 *
 * @param originInvoker 原始 Invoker
 * @return url 字符串
 */
private String getCacheKey(final Invoker<?> originInvoker) {
    URL providerUrl = getProviderUrl(originInvoker);
    return providerUrl.removeParameters("dynamic", "enabled").toFullString();
}
```

### DubboProtocol

```java
public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
    URL url = invoker.getUrl();

    // 创建 DubboExporter 对象，并添加到 `exporterMap` 。
    // export service.
    String key = serviceKey(url);
    DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
    exporterMap.put(key, exporter);

    //export an stub service for dispatching event
    Boolean isStubSupportEvent = url.getParameter(Constants.STUB_EVENT_KEY, Constants.DEFAULT_STUB_EVENT);
    Boolean isCallbackservice = url.getParameter(Constants.IS_CALLBACK_SERVICE, false);
    if (isStubSupportEvent && !isCallbackservice) {
        String stubServiceMethods = url.getParameter(Constants.STUB_EVENT_METHODS_KEY);
        if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
            if (logger.isWarnEnabled()) {
                logger.warn(new IllegalStateException("consumer [" + url.getParameter(Constants.INTERFACE_KEY) +
                        "], has set stubproxy support event ,but no stub methods founded."));
            }
        } else {
            stubServiceMethodsMap.put(url.getServiceKey(), stubServiceMethods);
        }
    }

    // 启动服务器
    openServer(url);
    // 初始化序列化优化器
    optimizeSerialization(url);
    return exporter;
}

protected static String serviceKey(URL url) {
    int port = url.getParameter(Constants.BIND_PORT_KEY, url.getPort());
    return serviceKey(port, url.getPath(), url.getParameter(Constants.VERSION_KEY),
            url.getParameter(Constants.GROUP_KEY));
}

```
serviceKey: ``dubbo://192.168.1.101:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&bind.ip=192.168.1.101&bind.port=20880&dubbo=2.0.0&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=52081&qos.port=22222&side=provider&timestamp=1599299597341`` ->  ``com.alibaba.dubbo.demo.DemoService:20880``



#### openServer
```java
/**
 * 通信客户端集合
 *
 * key: 服务器地址。格式为：host:port
 */
private final Map<String, ReferenceCountExchangeClient> referenceClientMap = new ConcurrentHashMap<String, ReferenceCountExchangeClient>(); // <host:port,Exchanger>

private void openServer(URL url) {
    // find server.
    String key = url.getAddress();
    //client can export a service which's only for server to invoke
    boolean isServer = url.getParameter(Constants.IS_SERVER_KEY, true);
    if (isServer) {
        ExchangeServer server = serverMap.get(key);
        if (server == null) {
            serverMap.put(key, createServer(url));
        } else {
            // server supports reset, use together with override
            server.reset(url);
        }
    }
}

private ExchangeServer createServer(URL url) {
    // 默认开启 server 关闭时发送 READ_ONLY 事件
    // send readonly event when server closes, it's enabled by default
    url = url.addParameterIfAbsent(Constants.CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString());
    // 默认开启 heartbeat
    // enable heartbeat by default
    url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));

    // 校验 Server 的 Dubbo SPI 拓展是否存在
    String str = url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_SERVER);

    if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str))
        throw new RpcException("Unsupported server type: " + str + ", url: " + url);

    // 设置编解码器为 `"Dubbo"`
    url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);

    // 启动服务器
    ExchangeServer server;
    try {
        server = Exchangers.bind(url, requestHandler);
    } catch (RemotingException e) {
        throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
    }

    // 校验 Client 的 Dubbo SPI 拓展是否存在
    str = url.getParameter(Constants.CLIENT_KEY);
    if (str != null && str.length() > 0) {
        Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
        if (!supportedTypes.contains(str)) {
            throw new RpcException("Unsupported client type: " + str);
        }
    }
    return server;
}
```

## Exporter

### ExporterChangeableWrapper

```java
/**
    * exporter proxy, establish the corresponding relationship between the returned exporter and the exporter exported by the protocol, and can modify the relationship at the time of override.
    *
    * @param <T>
    */
private class ExporterChangeableWrapper<T> implements Exporter<T> {

    /**
        * 原 Invoker 对象
        */
    private final Invoker<T> originInvoker;

    /**
        * 暴露的 Exporter 对象
        */
    private Exporter<T> exporter;

    public ExporterChangeableWrapper(Exporter<T> exporter, Invoker<T> originInvoker) {
        this.exporter = exporter;
        this.originInvoker = originInvoker;
    }

    public Invoker<T> getOriginInvoker() {
        return originInvoker;
    }

    public Invoker<T> getInvoker() {
        return exporter.getInvoker();
    }

    public void setExporter(Exporter<T> exporter) {
        this.exporter = exporter;
    }

    public void unexport() {
        String key = getCacheKey(this.originInvoker);
        bounds.remove(key);
        exporter.unexport();
    }
}

```


### DestroyableExporter
```java
static private class DestroyableExporter<T> implements Exporter<T> {

    public static final ExecutorService executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("Exporter-Unexport", true));

    /**
        * 原 Invoker 对象
        */
    private final Invoker<T> originInvoker;
    /**
        * 暴露的 Exporter 对象
        */
    private Exporter<T> exporter;
    private URL subscribeUrl;
    private URL registerUrl;

    public DestroyableExporter(Exporter<T> exporter, Invoker<T> originInvoker, URL subscribeUrl, URL registerUrl) {
        this.exporter = exporter;
        this.originInvoker = originInvoker;
        this.subscribeUrl = subscribeUrl;
        this.registerUrl = registerUrl;
    }

    public Invoker<T> getInvoker() {
        return exporter.getInvoker();
    }

    public void unexport() {
        Registry registry = RegistryProtocol.INSTANCE.getRegistry(originInvoker);
        try {
            registry.unregister(registerUrl);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            NotifyListener listener = RegistryProtocol.INSTANCE.overrideListeners.remove(subscribeUrl);
            registry.unsubscribe(subscribeUrl, listener);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }

        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    int timeout = ConfigUtils.getServerShutdownTimeout();
                    if (timeout > 0) {
                        logger.info("Waiting " + timeout + "ms for registry to notify all consumers before unexport. Usually, this is called when you use dubbo API");
                        Thread.sleep(timeout);
                    }
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        });
    }
}
```

### DubboExporter
```java
public class DubboExporter<T> extends AbstractExporter<T> {

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

    public DubboExporter(Invoker<T> invoker, String key, Map<String, Exporter<?>> exporterMap) {
        super(invoker);
        this.key = key;
        this.exporterMap = exporterMap;
    }

    @Override
    public void unexport() {
        // 取消暴露
        super.unexport();
        // 移除
        exporterMap.remove(key);
    }

}
```

