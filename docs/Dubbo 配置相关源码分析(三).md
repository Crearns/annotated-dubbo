# Dubbo 配置相关源码分析(三)

```java
// 当前应用配置
ApplicationConfig application = new ApplicationConfig();
application.setName("yyy");

// 连接注册中心配置
RegistryConfig registry = new RegistryConfig();
registry.setAddress("10.20.130.230:9090");
registry.setUsername("aaa");
registry.setPassword("bbb");

// 注意：ReferenceConfig为重对象，内部封装了与注册中心的连接，以及与服务提供方的连接

// 引用远程服务
ReferenceConfig<XxxService> reference = new ReferenceConfig<XxxService>(); // 此实例很重，封装了与注册中心的连接以及与提供者的连接，请自行缓存，否则可能造成内存和连接泄漏
reference.setApplication(application);
reference.setRegistry(registry); // 多个注册中心可以用setRegistries()
reference.setInterface(XxxService.class);
reference.setVersion("1.0.0");

// 和本地bean一样使用xxxService
XxxService xxxService = reference.get(); // 注意：此代理对象内部封装了所有通讯细节，对象较重，请缓存复用
```

上面是一段服务消费者的初始化代码

## ReferenceConfig
在文初的 ReferenceConfig 的初始化示例代码中，最后调用的是 ServiceConfig#get() 方法。从方法的命名，我们可以看出，获取引用服务。该方法主要做了如下几件事情：

* 进一步初始化 ReferenceConfig 对象。
* 校验 ReferenceConfig 对象的配置项。
* 使用 ReferenceConfig 对象，生成 Dubbo URL 对象数组。
* 使用 Dubbo URL 对象，应用服务。

```java
public synchronized T get() {
    // 已销毁，不可获得
    if (destroyed) {
        throw new IllegalStateException("Already destroyed!");
    }
    // 初始化
    if (ref == null) {
        init();
    }
    return ref;
}



private void init() {
    // 已经初始化，直接返回
    if (initialized) {
        return;
    }
    initialized = true;
    // 校验接口名非空
    if (interfaceName == null || interfaceName.length() == 0) {
        throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
    }
    // 拼接属性配置（环境变量 + properties 属性）到 ConsumerConfig 对象
    // get consumer's global configuration
    checkDefault();
    // 拼接属性配置（环境变量 + properties 属性）到 ReferenceConfig 对象
    appendProperties(this);
    // 若未设置 `generic` 属性，使用 `ConsumerConfig.generic` 属性。
    if (getGeneric() == null && getConsumer() != null) {
        setGeneric(getConsumer().getGeneric());
    }
    // 泛化接口的实现
    if (ProtocolUtils.isGeneric(getGeneric())) {
        interfaceClass = GenericService.class;
        // 普通接口的实现
    } else {
        try {
            interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                    .getContextClassLoader());
        } catch  (ClassNotFoundException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        // 校验接口和方法
        checkInterfaceAndMethods(interfaceClass, methods);
    }
    // 直连提供者，参见文档《直连提供者》http://dubbo.apache.org/zh-cn/docs/user/demos/explicit-target.html
    // 【直连提供者】第一优先级，通过 -D 参数指定 ，例如 java -Dcom.alibaba.xxx.XxxService=dubbo://localhost:20890
    String resolve = System.getProperty(interfaceName);
    String resolveFile = null;
    // 【直连提供者】第二优先级，通过文件映射，例如 com.alibaba.xxx.XxxService=dubbo://localhost:20890
    if (resolve == null || resolve.length() == 0) {
        // 默认先加载，`${user.home}/dubbo-resolve.properties` 文件 ，无需配置
        resolveFile = System.getProperty("dubbo.resolve.file");
        if (resolveFile == null || resolveFile.length() == 0) {
            File userResolveFile = new File(new File(System.getProperty("user.home")), "dubbo-resolve.properties");
            if (userResolveFile.exists()) {
                resolveFile = userResolveFile.getAbsolutePath();
            }
        }
        // 存在 resolveFile ，则进行文件读取加载。
        if (resolveFile != null && resolveFile.length() > 0) {
            Properties properties = new Properties();
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(new File(resolveFile));
                properties.load(fis);
            } catch (IOException e) {
                throw new IllegalStateException("Unload " + resolveFile + ", cause: " + e.getMessage(), e);
            } finally {
                try {
                    if (null != fis) fis.close();
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            resolve = properties.getProperty(interfaceName);
        }
    }
    // 设置直连提供者的 url
    if (resolve != null && resolve.length() > 0) {
        url = resolve;
        if (logger.isWarnEnabled()) {
            if (resolveFile != null && resolveFile.length() > 0) {
                logger.warn("Using default dubbo resolve file " + resolveFile + " replace " + interfaceName + "" + resolve + " to p2p invoke remote service.");
            } else {
                logger.warn("Using -D" + interfaceName + "=" + resolve + " to p2p invoke remote service.");
            }
        }
    }
    // 从 ConsumerConfig 对象中，读取 application、module、registries、monitor 配置对象。
    if (consumer != null) {
        if (application == null) {
            application = consumer.getApplication();
        }
        if (module == null) {
            module = consumer.getModule();
        }
        if (registries == null) {
            registries = consumer.getRegistries();
        }
        if (monitor == null) {
            monitor = consumer.getMonitor();
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
    // 校验 ApplicationConfig 配置。
    checkApplication();
    // 校验 Stub 和 Mock 相关的配置
    checkStubAndMock(interfaceClass);
    // 将 `side`，`dubbo`，`timestamp`，`pid` 参数，添加到 `map` 集合中。
    Map<String, String> map = new HashMap<String, String>();
    Map<Object, Object> attributes = new HashMap<Object, Object>();
    map.put(Constants.SIDE_KEY, Constants.CONSUMER_SIDE);
    map.put(Constants.DUBBO_VERSION_KEY, Version.getVersion());
    map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
    if (ConfigUtils.getPid() > 0) {
        map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
    }
    if (!isGeneric()) {
        String revision = Version.getVersion(interfaceClass, version);
        if (revision != null && revision.length() > 0) {
            map.put("revision", revision);
        }

        String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
        if (methods.length == 0) {
            logger.warn("NO method found in service interface " + interfaceClass.getName());
            map.put("methods", Constants.ANY_VALUE);
        } else {
            map.put("methods", StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
        }
    }
    map.put(Constants.INTERFACE_KEY, interfaceName);
    // 将各种配置对象，添加到 `map` 集合中。
    appendParameters(map, application);
    appendParameters(map, module);
    appendParameters(map, consumer, Constants.DEFAULT_KEY);
    appendParameters(map, this);
    // 获得服务键，作为前缀
    String prefix = StringUtils.getServiceKey(map);
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
            // 将带有 @Parameter(attribute = true) 配置对象的属性，添加到参数集合。参见《事件通知》http://dubbo.apache.org/zh-cn/docs/user/demos/events-notify.html
            appendAttributes(attributes, method, prefix + "." + method.getName());
            // 检查属性集合中的事件通知方法是否正确。若正确，进行转换。
            checkAndConvertImplicitConfig(method, map, attributes);
        }
    }

    // 以系统环境变量( DUBBO_IP_TO_REGISTRY ) 作为服务注册地址，参见 https://github.com/dubbo/dubbo-docker-sample 项目。
    String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
    if (hostToRegistry == null || hostToRegistry.length() == 0) {
        hostToRegistry = NetUtils.getLocalHost();
    } else if (isInvalidLocalHost(hostToRegistry)) {
        throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
    }
    map.put(Constants.REGISTER_IP_KEY, hostToRegistry);

    // 添加到 StaticContext 进行缓存
    //attributes are stored by system context.
    StaticContext.getSystemContext().putAll(attributes);
    ref = createProxy(map);
    ConsumerModel consumerModel = new ConsumerModel(getUniqueServiceName(), this, ref, interfaceClass.getMethods());
    ApplicationModel.initConsumerModel(getUniqueServiceName(), consumerModel);
}

```

