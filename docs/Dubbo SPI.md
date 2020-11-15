# Dubbo SPI

## Dubbo 的SPI背景

Dubbo 的扩展点加载从 JDK 标准的 SPI (Service Provider Interface) 扩展点发现机制加强而来。

Dubbo 改进了 JDK 标准的 SPI 的以下问题：

* JDK 标准的 SPI 会一次性实例化扩展点所有实现，如果有扩展实现初始化很耗时，但如果没用上也加载，会很浪费资源。
* 如果扩展点加载失败，连扩展点的名称都拿不到了。比如：JDK 标准的 ScriptEngine，通过 getName() 获取脚本类型的名称，但如果 RubyScriptEngine 因为所依赖的 jruby.jar 不存在，导致 RubyScriptEngine 类加载失败，这个失败原因被吃掉了，和 ruby 对应不起来，当用户执行 ruby 脚本时，会报不支持 ruby，而不是真正失败的原因。
* 增加了对扩展点 IoC 和 AOP 的支持，一个扩展点可以直接 setter 注入其它扩展点。


## ExtensionLoader
```java
private static final String SERVICES_DIRECTORY = "META-INF/services/";

private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

/**
* 拓展加载器集合
* key: 拓展接口
*/
private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

/**
* 拓展实现类集合
* key：拓展实现类
* value：拓展对象
*
* 例如，key 为 Class<AccessLogFilter>
* value 为 AccessLogFilter 对象
*/
private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

// ==============================

/**
* 拓展接口
* 例如：Protocol
*/
private final Class<?> type;

/**
* 对象工厂
* 功能上和 Spring IOC 一致
*
* 用于调用 #injectExtension(instance) 方法时，向创建的拓展注入其依赖的属性。例如，CacheFilter.cacheFactory 属性。
*
*
*/
private final ExtensionFactory objectFactory;

/**
* 缓存的拓展名与拓展类的映射。
* 和 {@link #cachedClasses} 的 KV 对调。
* 通过 {@link #loadExtensionClasses} 加载
*/
private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();

/**
* 缓存的拓展实现类集合。
*
* 不包含如下两种类型：
* 1. 自适应拓展实现类。例如 AdaptiveExtensionFactory
* 2. 带唯一参数为拓展接口的构造方法的实现类，或者说拓展 Wrapper 实现类。例如，ProtocolFilterWrapper 。
* 拓展 Wrapper 实现类，会添加到 {@link #cachedWrapperClasses} 中
*
* 通过 {@link #loadExtensionClasses} 加载
*/
private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();

/**
* 拓展名与 @Activate 的映射
* 例如，AccessLogFilter。
* 用于 {@link #getActivateExtension(URL, String)}
*
*/
private final Map<String, Activate> cachedActivates = new ConcurrentHashMap<String, Activate>();

/**
* 缓存的拓展对象集合
* key：拓展名
* value：拓展对象
*
* 例如，Protocol 拓展
* key：dubbo value：DubboProtocol
* key：injvm value：InjvmProtocol
* 通过 {@link #loadExtensionClasses} 加载
*/
private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();

/**
* 缓存的自适应( Adaptive )拓展对象
*/
private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();

/**
* 缓存的自适应拓展对象的类
* {@link #getAdaptiveExtensionClass()}
* TODO: 这有啥作用？
*/
private volatile Class<?> cachedAdaptiveClass = null;

/**
* 缓存的默认拓展名
* 通过 {@link SPI} 注解获得
*/
private String cachedDefaultName;

/**
* 创建 {@link #cachedAdaptiveInstance} 时发生的异常。
* 发生异常后，不再创建，参见 {@link #createAdaptiveExtension()}
*/
private volatile Throwable createAdaptiveInstanceError;

/**
* 拓展 Wrapper 实现类集合
* 带唯一参数为拓展接口的构造方法的实现类
* 通过 {@link #loadExtensionClasses} 加载
*
* TODO：作用？
*/
private Set<Class<?>> cachedWrapperClasses;

/**
* 拓展名 与 加载对应拓展类发生的异常 的 映射
*
* key：拓展名
* value：异常
*
* 在 {@link #loadFile(Map, String)} 时，记录
*/
private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

```

* 【静态属性】一方面，ExtensionLoaders 是 ExtensionLoader 的管理容器。一个拓展( 拓展接口 )对应一个 ExtensionLoader 对象。例如，Protocol 和 Filter 分别对应一个 ExtensionLoader 对象。
* 【对象属性】另一方面，一个拓展通过其 ExtensionLoader 对象，加载它的拓展实现们。我们会发现多个属性都是 “cached“ 开头。ExtensionLoader 考虑到性能和资源的优化，读取拓展配置后，会首先进行缓存。等到 Dubbo 代码真正用到对应的拓展实现时，进行拓展实现的对象的初始化。并且，初始化完成后，也会进行缓存。

### 获得拓展配置

#### getExtensionClasses
```java
private Map<String, Class<?>> getExtensionClasses() {
    // 从缓存中，获得拓展实现类数组
    Map<String, Class<?>> classes = cachedClasses.get();
    // double check
    if (classes == null) {
        synchronized (cachedClasses) {
            classes = cachedClasses.get();
            if (classes == null) {
                // 从配置文件中，加载拓展实现类数组
                classes = loadExtensionClasses();
                // 设置到缓存中
                cachedClasses.set(classes);
            }
        }
    }
    return classes;
}

```

#### loadExtensionClasses
```java
// synchronized in getExtensionClasses
private Map<String, Class<?>> loadExtensionClasses() {
    // 通过 @SPI 注解，获得默认的拓展实现类名
    final SPI defaultAnnotation = type.getAnnotation(SPI.class);
    if (defaultAnnotation != null) {
        String value = defaultAnnotation.value();
        if (value != null && (value = value.trim()).length() > 0) {
            String[] names = NAME_SEPARATOR.split(value);
            if (names.length > 1) {
                throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                        + ": " + Arrays.toString(names));
            }
            if (names.length == 1) cachedDefaultName = names[0];
        }
    }

    // 从配置文件中，加载拓展实现类数组
    Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
    loadFile(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
    loadFile(extensionClasses, DUBBO_DIRECTORY);
    loadFile(extensionClasses, SERVICES_DIRECTORY);
    return extensionClasses;
}
```

#### loadFile
```java
private void loadFile(Map<String, Class<?>> extensionClasses, String dir) {
    // 完整的文件名
    String fileName = dir + type.getName();
    try {
        Enumeration<java.net.URL> urls;
        // 获得文件名对应的所有文件数组
        ClassLoader classLoader = findClassLoader();
        if (classLoader != null) {
            urls = classLoader.getResources(fileName);
        } else {
            urls = ClassLoader.getSystemResources(fileName);
        }
        // 遍历文件数组
        if (urls != null) {
            while (urls.hasMoreElements()) {
                java.net.URL url = urls.nextElement();
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), "utf-8"));
                    try {
                        String line = null;
                        while ((line = reader.readLine()) != null) {
                            // 跳过当前被注释掉的情况，例如 #spring=xxxxxxxxx
                            final int ci = line.indexOf('#');
                            if (ci >= 0) line = line.substring(0, ci);
                            line = line.trim();
                            if (line.length() > 0) {
                                try {
                                    // 拆分，key=value 的配置格式
                                    String name = null;
                                    int i = line.indexOf('=');
                                    if (i > 0) {
                                        name = line.substring(0, i).trim();
                                        line = line.substring(i + 1).trim();
                                    }
                                    // =========================todo: 这段还得看看================================
                                    if (line.length() > 0) {
                                        // 判断拓展实现，是否实现拓展接口
                                        Class<?> clazz = Class.forName(line, true, classLoader);
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
                                        } else {
                                            // 缓存拓展 Wrapper 实现类到 `cachedWrapperClasses`
                                            try {
                                                // 通过反射的方式，参数为拓展接口，判断当前配置的拓展实现类为拓展 Wrapper 实现类。
                                                // 若成功（未抛出异常），则代表符合条件。
                                                clazz.getConstructor(type);
                                                Set<Class<?>> wrappers = cachedWrapperClasses;
                                                if (wrappers == null) {
                                                    cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                                                    wrappers = cachedWrapperClasses;
                                                }
                                                wrappers.add(clazz);
                                                // 缓存拓展实现类到 `extensionClasses`
                                            } catch (NoSuchMethodException e) {
                                                // 若获得构造方法失败，则代表是普通的拓展实现类，缓存到 extensionClasses 变量中
                                                clazz.getConstructor();
                                                // 未配置拓展名，自动生成。例如，DemoFilter 为 demo 。主要用于兼容 Java SPI 的配置。
                                                // 适用于 Java SPI 的配置方式
                                                if (name == null || name.length() == 0) {
                                                    name = findAnnotationName(clazz);
                                                    if (name == null || name.length() == 0) {
                                                        if (clazz.getSimpleName().length() > type.getSimpleName().length()
                                                                && clazz.getSimpleName().endsWith(type.getSimpleName())) {
                                                            name = clazz.getSimpleName().substring(0, clazz.getSimpleName().length() - type.getSimpleName().length()).toLowerCase();
                                                        } else {
                                                            throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + url);
                                                        }
                                                    }
                                                }
                                                // 获得拓展名，可以是数组，有多个拓展名。
                                                String[] names = NAME_SEPARATOR.split(name);
                                                if (names != null && names.length > 0) {
                                                    // 缓存 @Activate 到 `cachedActivates` 。
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
                                                        Class<?> c = extensionClasses.get(n);
                                                        if (c == null) {
                                                            extensionClasses.put(n, clazz);
                                                        } else if (c != clazz) {
                                                            throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    // =================================================================
                                } catch (Throwable t) {
                                    // 发生异常，记录到异常集合
                                    IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + url + ", cause: " + t.getMessage(), t);
                                    exceptions.put(line, e);
                                }
                            }
                        } // end of while read lines
                    } finally {
                        reader.close();
                    }
                } catch (Throwable t) {
                    logger.error("Exception when load extension class(interface: " +
                            type + ", class file: " + url + ") in " + url, t);
                }
            } // end of while urls
        }
    } catch (Throwable t) {
        logger.error("Exception when load extension class(interface: " +
                type + ", description file: " + fileName + ").", t);
    }
}
```

### 获得拓展加载器

#### getExtensionLoader
```java
public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
    if (type == null)
        throw new IllegalArgumentException("Extension type == null");
    // 必须是接口
    if (!type.isInterface()) {
        throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
    }
    // 必须包含 @SPI 注解
    if (!withExtensionAnnotation(type)) {
        throw new IllegalArgumentException("Extension type(" + type +
                ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
    }

    // 获得接口对应的拓展点加载器
    ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
    if (loader == null) {
        EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
        loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
    }
    return loader;
}


// 构造方法
private ExtensionLoader(Class<?> type) {
    this.type = type;
    objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
}
```

objectFactory 属性，对象工厂，功能上和 Spring IoC 一致。 todo: 再理解理解
* 用于调用 #injectExtension(instance) 方法时，向创建的拓展注入其依赖的属性。例如，CacheFilter.cacheFactory 属性。
* ``type == ExtensionFactory.class``当拓展接口非 ExtensionFactory 时( 如果不加这个判断，会是一个死循环 )，调用 ExtensionLoader#getAdaptiveExtension() 方法，获得 ExtensionFactory 拓展接口的自适应拓展实现对象。

### 获得指定拓展对象

#### getExtension
```java
public T getExtension(String name) {
    if (name == null || name.length() == 0)
        throw new IllegalArgumentException("Extension name == null");
    // 查找 默认的 拓展对象
    if ("true".equals(name)) {
        return getDefaultExtension();
    }
    // 从 缓存中 获得对应的拓展对象
    Holder<Object> holder = cachedInstances.get(name);
    if (holder == null) {
        cachedInstances.putIfAbsent(name, new Holder<Object>());
        holder = cachedInstances.get(name);
    }
    Object instance = holder.get();
    if (instance == null) {
        synchronized (holder) {
            instance = holder.get();
            // 从 缓存中 未获取到，进行创建缓存对象。
            if (instance == null) {
                instance = createExtension(name);
                // 设置创建对象到缓存中
                holder.set(instance);
            }
        }
    }
    return (T) instance;
}

public T getDefaultExtension() {
    getExtensionClasses();
    if (null == cachedDefaultName || cachedDefaultName.length() == 0
            || "true".equals(cachedDefaultName)) {
        return null;
    }
    return getExtension(cachedDefaultName);
}
```

#### createExtension

```java
private T createExtension(String name) {
    // 获得拓展名对应的拓展实现类
    Class<?> clazz = getExtensionClasses().get(name);
    if (clazz == null) {
        throw findException(name);
    }
    try {
        // 从缓存中，获得拓展对象。
        T instance = (T) EXTENSION_INSTANCES.get(clazz);
        if (instance == null) {
            // 当缓存不存在时，创建拓展对象，并添加到缓存中。
            EXTENSION_INSTANCES.putIfAbsent(clazz, (T) clazz.newInstance());
            instance = (T) EXTENSION_INSTANCES.get(clazz);
        }
        // 注入依赖的属性
        injectExtension(instance);
        // 创建 Wrapper 拓展对象
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
```
#### injectExtension
```java
private T injectExtension(T instance) {
    try {
        if (objectFactory != null) {
            for (Method method : instance.getClass().getMethods()) {
                if (method.getName().startsWith("set")
                        && method.getParameterTypes().length == 1
                        && Modifier.isPublic(method.getModifiers())) {
                    // 获得属性的类型
                    Class<?> pt = method.getParameterTypes()[0];
                    try {
                        // 获得属性
                        String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
                        // 获得属性值
                        Object object = objectFactory.getExtension(pt, property);
                        // 设置属性值
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
```

### 获得自适应的拓展对象
#### getAdaptiveExtension
```java
public T getAdaptiveExtension() {
    // 从缓存中，获得自适应拓展对象
    Object instance = cachedAdaptiveInstance.get();
    if (instance == null) {
        // 若之前未创建报错，
        if (createAdaptiveInstanceError == null) {
            synchronized (cachedAdaptiveInstance) {
                instance = cachedAdaptiveInstance.get();
                if (instance == null) {
                    try {
                        // 创建自适应拓展对象
                        instance = createAdaptiveExtension();
                        // 设置到缓存
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        // 记录异常
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        } else {
            // 若之前创建报错，则抛出异常 IllegalStateException
            throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
        }
    }

    return (T) instance;
}
```

#### createAdaptiveExtension
```java
private T createAdaptiveExtension() {
    try {
        return injectExtension((T) getAdaptiveExtensionClass().newInstance());
    } catch (Exception e) {
        throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
    }
}
```

#### getAdaptiveExtensionClass
```java
private Class<?> getAdaptiveExtensionClass() {
    getExtensionClasses();
    if (cachedAdaptiveClass != null) {
        return cachedAdaptiveClass;
    }
    return cachedAdaptiveClass = createAdaptiveExtensionClass();
}
```

#### createAdaptiveExtensionClass
```java
private Class<?> createAdaptiveExtensionClass() {
    // 自动生成自适应拓展的代码实现的字符串
    String code = createAdaptiveExtensionClassCode();
    // 编译代码，并返回该类
    ClassLoader classLoader = findClassLoader();
    com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
    return compiler.compile(code, classLoader);
}
```

其实就是生成代码并且动态编译

<a href="https://sm.ms/image/pqW4M8GQ2FsajdU" target="_blank"><img src="https://i.loli.net/2020/08/02/pqW4M8GQ2FsajdU.jpg" ></a>


### 获得激活的拓展对象数组  todo: 这个还得理解理解 等遇到再分析

#### getActivateExtension

```java
public List<T> getActivateExtension(URL url, String key, String group) {
    // 从 Dubbo URL 获得参数值
    String value = url.getParameter(key);
    // 获得符合自动激活条件的拓展对象数组
    return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
}


public List<T> getActivateExtension(URL url, String[] values, String group) {
    List<T> exts = new ArrayList<T>();
    List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
    // 处理自动激活的拓展对象们
    // 判断不存在配置 `"-name"` 。例如，<dubbo:service filter="-default" /> ，代表移除所有默认过滤器。
    if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
        // 获得拓展实现类数组
        getExtensionClasses();
        // 循环
        for (Map.Entry<String, Activate> entry : cachedActivates.entrySet()) {
            String name = entry.getKey();
            Activate activate = entry.getValue();
            if (isMatchGroup(group, activate.group())) { // 匹配分组
                // 获得拓展对象
                T ext = getExtension(name);
                if (!names.contains(name) // 不包含在自定义配置里。如果包含，会在下面的代码处理。
                        && !names.contains(Constants.REMOVE_VALUE_PREFIX + name) // 判断是否配置移除。例如 <dubbo:service filter="-monitor" />，则 MonitorFilter 会被移除
                        && isActive(activate, url)) { // 判断是否激活
                    exts.add(ext);
                }
            }
        }
        // 排序
        Collections.sort(exts, ActivateComparator.COMPARATOR);
    }
    // 处理自定义配置的拓展对象们。例如在 <dubbo:service filter="demo" /> ，代表需要加入 DemoFilter （这个是笔者自定义的）。
    List<T> usrs = new ArrayList<T>();
    for (int i = 0; i < names.size(); i++) {
        String name = names.get(i);
        if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {// 判断非移除的
            // 将配置的自定义在自动激活的拓展对象们前面。例如，<dubbo:service filter="demo,default,demo2" /> ，则 DemoFilter 就会放在默认的过滤器前面。
            if (Constants.DEFAULT_KEY.equals(name)) {
                if (!usrs.isEmpty()) {
                    exts.addAll(0, usrs);
                    usrs.clear();
                }
            } else {
                // 获得拓展对象
                T ext = getExtension(name);
                usrs.add(ext);
            }
        }
    }
    if (!usrs.isEmpty()) {
        exts.addAll(usrs);
    }
    return exts;
}
```

