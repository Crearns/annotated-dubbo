# Dubbo 配置相关源码分析(二)

主要分析服务提供者配置的主要逻辑，下面是服务提供者初始化代码

```java
// 服务实现
XxxService xxxService = new XxxServiceImpl();

// 当前应用配置
ApplicationConfig application = new ApplicationConfig();
application.setName("xxx");

// 连接注册中心配置
RegistryConfig registry = new RegistryConfig();
registry.setAddress("10.20.130.230:9090");
registry.setUsername("aaa");
registry.setPassword("bbb");

// 服务提供者协议配置
ProtocolConfig protocol = new ProtocolConfig();
protocol.setName("dubbo");
protocol.setPort(12345);
protocol.setThreads(200);

// 注意：ServiceConfig为重对象，内部封装了与注册中心的连接，以及开启服务端口

// 服务提供者暴露服务配置
ServiceConfig<XxxService> service = new ServiceConfig<XxxService>(); // 此实例很重，封装了与注册中心的连接，请自行缓存，否则可能造成内存和连接泄漏
service.setApplication(application);
service.setRegistry(registry); // 多个注册中心可以用setRegistries()
service.setProtocol(protocol); // 多个协议可以用setProtocols()
service.setInterface(XxxService.class);
service.setRef(xxxService);
service.setVersion("1.0.0");

// 暴露及注册服务
service.export();
```


## ServiceConfig

主要看export方法，从方法的命名，我们可以看出，暴露服务。该方法主要做了如下几件事情：

1. 进一步初始化 ServiceConfig 对象。
2. 校验 ServiceConfig 对象的配置项。
3. 使用 ServiceConfig 对象，生成 Dubbo URL 对象数组。
4. 使用 Dubbo URL 对象，暴露服务。

本文重点在服务提供者相关的配置，因此只解析 1+2+3 部分( 不包括 4 )

```java
public synchronized void export() {
    // 当 export 或者 delay 未配置，从 ProviderConfig 对象读取
    if (provider != null) {
        if (export == null) {
            export = provider.getExport();
        }
        if (delay == null) {
            delay = provider.getDelay();
        }
    }

    // 不暴露服务( export = false ) ，则不进行暴露服务逻辑。
    if (export != null && !export) {
        return;
    }

    // 延迟暴露 如果你的服务需要预热时间，比如初始化缓存，等待相关资源就位等，可以使用 delay 进行延迟暴露。
    if (delay != null && delay > 0) {
        delayExportExecutor.schedule(new Runnable() {
            public void run() {
                doExport();
            }
        }, delay, TimeUnit.MILLISECONDS);
    } else {
        // 立即暴露
        doExport();
    }
}


protected synchronized void doExport() {
    // 检查是否可以暴露，若可以，标记已经暴露。
    if (unexported) {
        throw new IllegalStateException("Already unexported!");
    }
    if (exported) {
        return;
    }
    exported = true;
    // 校验接口名非空
    if (interfaceName == null || interfaceName.length() == 0) {
        throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
    }
    checkDefault();

    // 从 ProviderConfig 对象中，读取 application、module、registries、monitor、protocols 配置对象。
    if (provider != null) {
        if (application == null) {
            application = provider.getApplication();
        }
        if (module == null) {
            module = provider.getModule();
        }
        if (registries == null) {
            registries = provider.getRegistries();
        }
        if (monitor == null) {
            monitor = provider.getMonitor();
        }
        if (protocols == null) {
            protocols = provider.getProtocols();
        }
    }

    // 从 ModuleConfig 对象中，读取 registries、monitor 配置对象。
    if (module != null) {
        if (registries == null) {
            registries = module.getRegistries();
        }
        if (monitor == null) {
            monitor = module.getMonitor();
        }
    }

    // 从 ApplicationConfig 对象中，读取 registries、monitor 配置对象。
    if (application != null) {
        if (registries == null) {
            registries = application.getRegistries();
        }
        if (monitor == null) {
            monitor = application.getMonitor();
        }
    }

    // 泛化接口的实现
    if (ref instanceof GenericService) {
        interfaceClass = GenericService.class;
        if (StringUtils.isEmpty(generic)) {
            generic = Boolean.TRUE.toString();
        }
        // 普通接口的实现
    } else {
        try {
            interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                    .getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        // 校验接口和方法
        checkInterfaceAndMethods(interfaceClass, methods);
        // 校验指向的 service 对象
        checkRef();
        generic = Boolean.FALSE.toString();
    }

    // 处理服务接口客户端本地代理( `local` )相关。实际目前已经废弃，使用 `stub` 属性，参见 `AbstractInterfaceConfig#setLocal` 方法。
    if (local != null) {
        // 设为 true，表示使用缺省代理类名，即：接口名 + Local 后缀
        if ("true".equals(local)) {
            local = interfaceName + "Local";
        }
        Class<?> localClass;
        try {
            localClass = ClassHelper.forNameWithThreadContextClassLoader(local);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        if (!interfaceClass.isAssignableFrom(localClass)) {
            throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
        }
    }

    // 处理服务接口客户端本地代理( `stub` )相关
    if (stub != null) {

        // 设为 true，表示使用缺省代理类名，即：接口名 + Stub 后缀
        if ("true".equals(stub)) {
            stub = interfaceName + "Stub";
        }
        Class<?> stubClass;
        try {
            stubClass = ClassHelper.forNameWithThreadContextClassLoader(stub);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        if (!interfaceClass.isAssignableFrom(stubClass)) {
            throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
        }
    }
    // 校验 ApplicationConfig 配置。
    checkApplication();
    // 校验 RegistryConfig 配置。
    checkRegistry();
    // 校验 ProtocolConfig 配置数组。
    checkProtocol();
    // 读取环境变量和 properties 配置到 ServiceConfig 对象。
    appendProperties(this);
    // 校验 Stub 和 Mock 相关的配置
    checkStubAndMock(interfaceClass);
    // 服务路径，缺省为接口名
    if (path == null || path.length() == 0) {
        path = interfaceName;
    }
    // 暴露服务
    doExportUrls();
    ProviderModel providerModel = new ProviderModel(getUniqueServiceName(), this, ref);
    ApplicationModel.initProviderModel(getUniqueServiceName(), providerModel);
}



/**
* 暴露 Dubbo URL
*/
@SuppressWarnings({"unchecked", "rawtypes"})
private void doExportUrls() {
    // 应该是获取keeper的地址
    List<URL> registryURLs = loadRegistries(true);
    for (ProtocolConfig protocolConfig : protocols) {
        doExportUrlsFor1Protocol(protocolConfig, registryURLs);
    }
}

private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
    // 协议名
    String name = protocolConfig.getName();
    if (name == null || name.length() == 0) {
        name = "dubbo";
    }

    // 将 `side`，`dubbo`，`timestamp`，`pid` 参数，添加到 `map` 集合中。
    Map<String, String> map = new HashMap<String, String>();
    map.put(Constants.SIDE_KEY, Constants.PROVIDER_SIDE);
    map.put(Constants.DUBBO_VERSION_KEY, Version.getVersion());
    map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
    if (ConfigUtils.getPid() > 0) {
        map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
    }

    // 将各种配置对象，添加到 `map` 集合中。 参考上篇文章
    appendParameters(map, application);
    appendParameters(map, module);
    appendParameters(map, provider, Constants.DEFAULT_KEY);
    appendParameters(map, protocolConfig);
    appendParameters(map, this);

    // 将 MethodConfig 对象数组，添加到 `map` 集合中。
    if (methods != null && !methods.isEmpty()) {
        for (MethodConfig method : methods) {
            // 将 MethodConfig 对象，添加到 `map` 集合中。
            appendParameters(map, method, method.getName());
            // 当 配置了 `MethodConfig.retry = false` 时，强制禁用重试
            String retryKey = method.getName() + ".retry";
            if (map.containsKey(retryKey)) {
                String retryValue = map.remove(retryKey);
                if ("false".equals(retryValue)) {
                    map.put(method.getName() + ".retries", "0");
                }
            }
            // 将 ArgumentConfig 对象数组，添加到 `map` 集合中。
            List<ArgumentConfig> arguments = method.getArguments();
            if (arguments != null && !arguments.isEmpty()) {
                for (ArgumentConfig argument : arguments) {
                    // convert argument type
                    if (argument.getType() != null && argument.getType().length() > 0) { // 指定了类型
                        Method[] methods = interfaceClass.getMethods();
                        // visit all methods
                        if (methods != null && methods.length > 0) {
                            for (int i = 0; i < methods.length; i++) {
                                String methodName = methods[i].getName();
                                // target the method, and get its signature
                                if (methodName.equals(method.getName())) { // 找到指定方法
                                    Class<?>[] argtypes = methods[i].getParameterTypes();
                                    // one callback in the method
                                    if (argument.getIndex() != -1) { // 指定单个参数的位置 + 类型
                                        if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                            // 将 ArgumentConfig 对象，添加到 `map` 集合中。
                                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                        } else {
                                            throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                        }
                                    } else {
                                        // multiple callbacks in the method
                                        for (int j = 0; j < argtypes.length; j++) {
                                            Class<?> argclazz = argtypes[j];
                                            if (argclazz.getName().equals(argument.getType())) {
                                                // 将 ArgumentConfig 对象，添加到 `map` 集合中。
                                                appendParameters(map, argument, method.getName() + "." + j); // `${methodName}.${index}`
                                                if (argument.getIndex() != -1 && argument.getIndex() != j) { // 多余的判断，因为 `argument.getIndex() == -1` 。
                                                    throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (argument.getIndex() != -1) { // 指定单个参数的位置
                        // 将 ArgumentConfig 对象，添加到 `map` 集合中。
                        appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                    } else {
                        throw new IllegalArgumentException("argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                    }

                }
            }
        } // end of methods for
    }
    
    // 泛化接口
    if (ProtocolUtils.isGeneric(generic)) {
        map.put("generic", generic);
        map.put("methods", Constants.ANY_VALUE);
    } else {
        String revision = Version.getVersion(interfaceClass, version);
        if (revision != null && revision.length() > 0) {
            map.put("revision", revision); // 修订号
        }

        String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();// 获得方法数组
        if (methods.length == 0) {
            logger.warn("NO method found in service interface " + interfaceClass.getName());
            map.put("methods", Constants.ANY_VALUE);
        } else {
            map.put("methods", StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
        }
    }
    // token ，参见《令牌校验》http://dubbo.apache.org/zh-cn/docs/user/demos/token-authorization.html
    if (!ConfigUtils.isEmpty(token)) {
        if (ConfigUtils.isDefault(token)) {
            map.put("token", UUID.randomUUID().toString());
        } else {
            map.put("token", token);
        }
    }
    // 协议为 injvm 时，不注册，不通知。
    if ("injvm".equals(protocolConfig.getName())) {
        protocolConfig.setRegister(false);
        map.put("notify", "false");
    }
    // export service
    String contextPath = protocolConfig.getContextpath();
    if ((contextPath == null || contextPath.length() == 0) && provider != null) {
        contextPath = provider.getContextpath();
    }

    String host = this.findConfigedHosts(protocolConfig, registryURLs, map);
    Integer port = this.findConfigedPorts(protocolConfig, name, map);
    // 创建 Dubbo URL 对象
    URL url = new URL(name, host, port, (contextPath == null || contextPath.length() == 0 ? "" : contextPath + "/") + path, map);

    // 配置规则，参见《配置规则》http://dubbo.apache.org/zh-cn/docs/user/demos/config-rule.html
    if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
            .hasExtension(url.getProtocol())) {
        url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
    }

    // ...
}

```