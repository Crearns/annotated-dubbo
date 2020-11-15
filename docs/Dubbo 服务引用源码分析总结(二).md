# Dubbo 服务引用源码分析总结(二)

<a href="https://sm.ms/image/PYO4wgxWMLlSdTV" target="_blank"><img src="https://i.loli.net/2020/09/12/PYO4wgxWMLlSdTV.jpg" ></a>

相比本地引用，远程引用会多做如下几件事情：

* 向注册中心订阅，从而发现服务提供者列表。
* 启动通信客户端，通过它进行远程调用。

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
        // 省略本地引用
    } else {
        // 定义直连地址，可以是服务提供者的地址，也可以是注册中心的地址
        if (url != null && url.length() > 0) { // user specified URL, could be peer-to-peer address, or register center's address.
            // 拆分地址成数组，使用 ";" 分隔。
            String[] us = Constants.SEMICOLON_SPLIT_PATTERN.split(url);
            // 循环数组，添加到 `url` 中。
            if (us != null && us.length > 0) {
                for (String u : us) {
                    // 创建 URL 对象
                    URL url = URL.valueOf(u);
                    // 设置默认路径
                    if (url.getPath() == null || url.getPath().length() == 0) {
                        url = url.setPath(interfaceName);
                    }
                    // 注册中心的地址，带上服务引用的配置参数
                    if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                        urls.add(url.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                    } else {
                        // 服务提供者的地址
                        urls.add(ClusterUtils.mergeUrl(url, map));
                    }
                }
            }
            // 注册中心
        } else { // assemble URL from register center's configuration
            // 加载注册中心 URL 数组
            List<URL> us = loadRegistries(false);
            // 循环数组，添加到 `url` 中。
            if (us != null && !us.isEmpty()) {
                for (URL u : us) {
                    // 加载监控中心 URL
                    URL monitorUrl = loadMonitor(u);
                    // 服务引用配置对象 `map`，带上监控中心的 URL
                    if (monitorUrl != null) {
                        map.put(Constants.MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
                    }
                    // 注册中心的地址，带上服务引用的配置参数
                    urls.add(u.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                }
            }
            if (urls == null || urls.isEmpty()) {
                throw new IllegalStateException("No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", please config <dubbo:registry address=\"...\" /> to your spring config.");
            }
        }

        // 单 `urls` 时，引用服务，返回 Invoker 对象
        if (urls.size() == 1) {
            // 引用服务
            invoker = refprotocol.refer(interfaceClass, urls.get(0));
        } else {
            // 循环 `urls` ，引用服务，返回 Invoker 对象
            List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
            URL registryURL = null;
            for (URL url : urls) {
                // 引用服务
                invokers.add(refprotocol.refer(interfaceClass, url));
                // 使用最后一个注册中心的 URL
                if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                    registryURL = url; // use last registry url
                }
            }
            // 有注册中心 todo 集群容错
            if (registryURL != null) { // registry url is available
                // 对有注册中心的 Cluster 只用 AvailableCluster
                // use AvailableCluster only when register's cluster is available
                URL u = registryURL.addParameter(Constants.CLUSTER_KEY, AvailableCluster.NAME);
                invoker = cluster.join(new StaticDirectory(u, invokers));
            } else { // not a registry url
                // 无注册中心
                invoker = cluster.join(new StaticDirectory(invokers));
            }
        }
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
```


和服务调用很像，主要是通过SPI的机制，分别调用 adapter、filter、listener、Protocol 进行调用，并且会有两条链，一条是注册中心、另一条是服务本身引用。

Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => RegistryProtocol
=>
Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => DubboProtocol

也就是说，这一条大的调用链，包含两条小的调用链。原因是：
* 首先，传入的是注册中心的 URL ，通过 Protocol$Adaptive 获取到的是 RegistryProtocol 对象。
* 其次，RegistryProtocol 会在其 #refer(...) 方法中，使用服务提供者的 URL ( 即注册中心的 URL 的 refer 参数值)，再次调用 Protocol$Adaptive 获取到的是 DubboProtocol 对象，进行服务引用。

```
RegistryProtocol#refer(...) {
    
    // 1. 获取服务提供者列表 【并且订阅】
    
    // 2. 创建调用连接服务提供者的客户端 
    DubboProtocol#refer(...);
    
    // ps：实际这个过程中，还有别的代码，详细见下文。
}
```


## ProtocolFilterWrapper

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

## RegistryProtocol

### refer
```java
public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
    // 获得真实的注册中心的 URL
    url = url.setProtocol(url.getParameter(Constants.REGISTRY_KEY, Constants.DEFAULT_REGISTRY)).removeParameter(Constants.REGISTRY_KEY);
    // 获得注册中心
    Registry registry = registryFactory.getRegistry(url);
    if (RegistryService.class.equals(type)) {
        return proxyFactory.getInvoker((T) registry, type, url);
    }

    // 获得服务引用配置参数集合
    // group="a,b" or group="*"
    Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
    String group = qs.get(Constants.GROUP_KEY);
    // 分组聚合，参见文档 http://dubbo.apache.org/zh-cn/docs/user/demos/group-merger.html
    if (group != null && group.length() > 0) {
        if ((Constants.COMMA_SPLIT_PATTERN.split(group)).length > 1
                || "*".equals(group)) {
            // 执行服务引用
            return doRefer(getMergeableCluster(), registry, type, url);
        }
    }
    // 执行服务引用
    return doRefer(cluster, registry, type, url);
}


private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
    // 创建 RegistryDirectory 对象，并设置注册中心
    RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);
    directory.setRegistry(registry);
    directory.setProtocol(protocol);
    // all attributes of REFER_KEY
    // 创建订阅 URL
    Map<String, String> parameters = new HashMap<String, String>(directory.getUrl().getParameters());
    URL subscribeUrl = new URL(Constants.CONSUMER_PROTOCOL, parameters.remove(Constants.REGISTER_IP_KEY), 0, type.getName(), parameters);
    // 向注册中心注册自己（服务消费者）
    if (!Constants.ANY_VALUE.equals(url.getServiceInterface())
            && url.getParameter(Constants.REGISTER_KEY, true)) {
        registry.register(subscribeUrl.addParameters(Constants.CATEGORY_KEY, Constants.CONSUMERS_CATEGORY,
                Constants.CHECK_KEY, String.valueOf(false)));
    }
    // 向注册中心订阅服务提供者
    directory.subscribe(subscribeUrl.addParameter(Constants.CATEGORY_KEY,
            Constants.PROVIDERS_CATEGORY
                    + "," + Constants.CONFIGURATORS_CATEGORY
                    + "," + Constants.ROUTERS_CATEGORY));

    // 创建 Invoker 对象
    Invoker invoker = cluster.join(directory);
    // 向本地注册表，注册消费者
    ProviderConsumerRegTable.registerConsumer(invoker, url, subscribeUrl, directory);
    return invoker;
}
```


## DubboProtocol

### refer

```java
public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
    // 初始化序列化优化器 todo
    optimizeSerialization(url);
    // 获得远程通信客户端数组
    // 创建 DubboInvoker 对象
    // create rpc invoker.
    DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
    // 添加到 `invokers`
    invokers.add(invoker);
    return invoker;
}


private ExchangeClient[] getClients(URL url) {
    // 是否共享连接
    // whether to share connection
    boolean service_share_connect = false;
    int connections = url.getParameter(Constants.CONNECTIONS_KEY, 0);
    // if not configured, connection is shared, otherwise, one connection for one service
    if (connections == 0) { // 未配置时，默认共享
        service_share_connect = true;
        connections = 1;
    }

    // 创建连接服务提供者的 ExchangeClient 对象数组
    ExchangeClient[] clients = new ExchangeClient[connections];
    for (int i = 0; i < clients.length; i++) {
        if (service_share_connect) {
            clients[i] = getSharedClient(url);
        } else {
            clients[i] = initClient(url);
        }
    }
    return clients;
}


/**
    * Get shared connection
    *
    * todo 客户端
    */
private ExchangeClient getSharedClient(URL url) {
    // 从集合中，查找 ReferenceCountExchangeClient 对象
    String key = url.getAddress();
    ReferenceCountExchangeClient client = referenceClientMap.get(key);
    if (client != null) {
        // 若未关闭，增加指向该 Client 的数量，并返回它
        if (!client.isClosed()) {
            client.incrementAndGetCount();
            return client;
        } else {
            // 若已关闭，移除
            referenceClientMap.remove(key);
        }
    }
    // 同步，创建 ExchangeClient 对象。
    synchronized (key.intern()) {
        // 创建 ExchangeClient 对象
        ExchangeClient exchangeClient = initClient(url);
        // 将 `exchangeClient` 包装，创建 ReferenceCountExchangeClient 对象
        client = new ReferenceCountExchangeClient(exchangeClient, ghostClientMap);
        // 添加到集合
        referenceClientMap.put(key, client);
        // 添加到 `ghostClientMap`
        ghostClientMap.remove(key);
        return client;
    }
}


private ExchangeClient initClient(URL url) {

    // 校验 Client 的 Dubbo SPI 拓展是否存在
    // client type setting.
    String str = url.getParameter(Constants.CLIENT_KEY, url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_CLIENT));

    // 设置编解码器为 Dubbo ，即 DubboCountCodec
    url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);

    // 默认开启 heartbeat
    // enable heartbeat by default
    url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));

    // BIO is not allowed since it has severe performance issue.
    if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
        throw new RpcException("Unsupported client type: " + str + "," +
                " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
    }

    // 连接服务器，创建客户端
    ExchangeClient client;
    try {
        // 懒连接，创建 LazyConnectExchangeClient 对象
        // connection should be lazy
        if (url.getParameter(Constants.LAZY_CONNECT_KEY, false)) {
            client = new LazyConnectExchangeClient(url, requestHandler);
        } else {
            // 直接连接，创建 HeaderExchangeClient 对象
            client = Exchangers.connect(url, requestHandler);
        }
    } catch (RemotingException e) {
        throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
    }
    return client;
}
```