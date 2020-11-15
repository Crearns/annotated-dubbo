# Dubbo 注册中心源码分析-抽象API

## RegistryFactory

```java
@SPI("dubbo")
public interface RegistryFactory {

    /**
     * 连接注册中心.
     * <p>
     * 连接注册中心需处理契约：<br>
     * 1. 当设置check=false时表示不检查连接，否则在连接不上时抛出异常。<br>
     * 2. 支持URL上的username:password权限认证。<br>
     * 3. 支持backup=10.20.153.10备选注册中心集群地址。<br>
     * 4. 支持file=registry.cache本地磁盘文件缓存。<br>
     * 5. 支持timeout=1000请求超时设置。<br>
     * 6. 支持session=60000会话超时或过期设置。<br>
     *
     * @param url 注册中心地址，不允许为空
     * @return 注册中心引用，总不返回空
     */
    @Adaptive({"protocol"})
    Registry getRegistry(URL url);

}
```

RegistryFactory 是一个 Dubbo SPI 拓展接口。#getRegistry(url) 方法，获得注册中心 Registry 对象。
* 注意方法上注释的处理契约。
* @Adaptive({"protocol"}) 注解，Dubbo SPI 会自动实现 RegistryFactory$Adaptive 类，根据 url.protocol 获得对应的 RegistryFactory 实现类。例如，url.protocol = zookeeper 时，获得 ZookeeperRegistryFactory 实现类。


## AbstractRegistryFactory

实现了 Registry 的容器管理。

```java
// The lock for the acquisition process of the registry
private static final ReentrantLock LOCK = new ReentrantLock();

/**
 * Registry 集合
 *
 * key：{@link URL#toServiceString()}
 */
// Registry Collection Map<RegistryAddress, Registry>
private static final Map<String, Registry> REGISTRIES = new ConcurrentHashMap<String, Registry>();
```



### createRegistry

```java
/**
    * 创建 Registry 对象
    *
    * @param url 注册中心地址
    * @return Registry 对象
    */
protected abstract Registry createRegistry(URL url);
```

这个方法主要是构造Registry对象，由子类字节实现


## RegistryService

注册中心服务接口，定义了注册、订阅、查询三种操作方法，如下：

* #register(url) 方法，注册数据，比如：提供者地址，消费者地址，路由规则，覆盖规则，等数据。
* #unregister(url) 方法，取消注册。
* #subscribe(url, NotifyListener) 方法，订阅符合条件的已注册数据，当有注册数据变更时自动推送。

* #unsubscribe(url, NotifyListener) 方法，取消订阅。
    
    在 URL.parameters.category 属性上，表示订阅的数据分类。目前有四种类型：
    * consumers ，服务消费者列表。
    * providers ，服务提供者列表。
    * routers ，路由规则列表。
    * configurations ，配置规则列表。
* #lookup(url) 方法，查询符合条件的已注册数据，与订阅的推模式相对应，这里为拉模式，只返回一次结果。

### Registry

Registry 继承了 RegistryService 与 Node 接口

```java

// URL地址分隔符，用于文件缓存中，服务提供者URL分隔
// URL address separator, used in file cache, service provider URL separation
private static final char URL_SEPARATOR = ' ';
// URL地址分隔正则表达式，用于解析文件缓存中服务提供者URL列表
// URL address separated regular expression for parsing the service provider URL list in the file cache
private static final String URL_SPLIT = "\\s+";
// Log output
protected final Logger logger = LoggerFactory.getLogger(getClass());
/**
    * 本地磁盘缓存。
    * 1. 其中特殊的 key 值 .registies 记录注册中心列表
    * 2. 其它均为 {@link #notified} 服务提供者列表
    */
// Local disk cache, where the special key value.registies records the list of registry centers, and the others are the list of notified service providers
private final Properties properties = new Properties();
//注册中心缓存写入执行器。 线程数为1
// File cache timing writing
private final ExecutorService registryCacheExecutor = Executors.newFixedThreadPool(1, new NamedThreadFactory("DubboSaveRegistryCache", true));
// 是否同步保存文件
// Is it synchronized to save the file
private final boolean syncSaveFile;
// 数据版本号
private final AtomicLong lastCacheChanged = new AtomicLong();
// 已注册 URL 集合。
// 注意，注册的 URL 不仅仅可以是服务提供者的，也可以是服务消费者的
private final Set<URL> registered = new ConcurrentHashSet<URL>();
/**
    * 订阅 URL 的监听器集合
    * key：消费者的 URL ，例如消费者的 URL
    */
private final ConcurrentMap<URL, Set<NotifyListener>> subscribed = new ConcurrentHashMap<URL, Set<NotifyListener>>();
/**
    * 被通知的 URL 集合
    * key1：消费者的 URL ，例如消费者的 URL ，和 {@link #subscribed} 的键一致
    * key2：分类，例如：providers、consumers、routes、configurators。【实际无 consumers ，因为消费者不会去订阅另外的消费者的列表】
    * 在 {@link Constants} 中，以 "_CATEGORY" 结尾
    */
private final ConcurrentMap<URL, Map<String, List<URL>>> notified = new ConcurrentHashMap<URL, Map<String, List<URL>>>();
// 注册中心 URL
private URL registryUrl;
// 本地磁盘缓存文件，缓存注册中心的数据
// Local disk cache file
private File file;


public AbstractRegistry(URL url) {
    setUrl(url);
    // Start file save timer
    syncSaveFile = url.getParameter(Constants.REGISTRY_FILESAVE_SYNC_KEY, false);
    // 获得 `file`
    String filename = url.getParameter(Constants.FILE_KEY, System.getProperty("user.home") + "/.dubbo/dubbo-registry-" + url.getParameter(Constants.APPLICATION_KEY) + "-" + url.getAddress() + ".cache");
    File file = null;
    if (ConfigUtils.isNotEmpty(filename)) {
        file = new File(filename);
        if (!file.exists() && file.getParentFile() != null && !file.getParentFile().exists()) {
            if (!file.getParentFile().mkdirs()) {
                throw new IllegalArgumentException("Invalid registry store file " + file + ", cause: Failed to create directory " + file.getParentFile() + "!");
            }
        }
    }
    this.file = file;
    // 加载本地磁盘缓存文件到内存缓存
    loadProperties();
    // 通知监听器，URL 变化结果
    notify(url.getBackupUrls());
}
```


#### register && unregister

* #register(url)

    从实现上，我们可以看出，并未向注册中心发起注册，仅仅是添加到 registered 中，进行状态的维护。实际上，真正的实现在 FailbackRegistry 类中。
* #unregister(url)

    和 #register(url) 的处理方式相同。

#### subscribe && unsubscribe

* #subscribe(url, listener)

    和 #register(url) 的处理方式相同。
* #unsubscribe(url, listener)

    和 #register(url) 的处理方式相同。

#### notify

#notify(url, listener, urls) 方法，通知监听器，URL 变化结果。这里我们有两点要注意下：

* 第一，向注册中心发起订阅后，会获取到全量数据，此时会被调用 #notify(...) 方法，即 Registry 获取到了全量数据。
* 第二，每次注册中心发生变更时，会调用 #notify(...) 方法，虽然变化是增量，调用这个方法的调用方，已经进行处理，传入的 urls 依然是全量的。

```java
/**
    * 向注册中心发起订阅后，会获取到全量数据，此时会被调用 #notify(...) 方法，即 Registry 获取到了全量数据。
    * 每次注册中心发生变更时，会调用 #notify(...) 方法，虽然变化是增量，调用这个方法的调用方，已经进行处理，传入的 urls 依然是全量的。
    * @param urls 注册的urls
    */
protected void notify(List<URL> urls) {
    if (urls == null || urls.isEmpty()) return;

    for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
        URL url = entry.getKey();

        if (!UrlUtils.isMatch(url, urls.get(0))) {
            continue;
        }

        Set<NotifyListener> listeners = entry.getValue();
        if (listeners != null) {
            for (NotifyListener listener : listeners) {
                try {
                    notify(url, listener, filterEmpty(url, urls));
                } catch (Throwable t) {
                    logger.error("Failed to notify registry event, urls: " + urls + ", cause: " + t.getMessage(), t);
                }
            }
        }
    }
}
```

## FailbackRegistry

实现 AbstractRegistry 抽象类，支持失败重试的 Registry 抽象类。AbstractRegistry 进行的注册、订阅等操作，更多的是修改状态，而无和注册中心实际的操作。FailbackRegistry 在 AbstractRegistry 的基础上，实现了和注册中心实际的操作，并且支持失败重试的特性。

```java
// 定时任务执行器
// Scheduled executor service
private final ScheduledExecutorService retryExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboRegistryFailedRetryTimer", true));

// 失败重试定时器，定时检查是否有请求失败，如有，无限次重试
// Timer for failure retry, regular check if there is a request for failure, and if there is, an unlimited retry
private final ScheduledFuture<?> retryFuture;

// 失败发起注册失败的 URL 集合
private final Set<URL> failedRegistered = new ConcurrentHashSet<URL>();

// 失败取消注册失败的 URL 集合
private final Set<URL> failedUnregistered = new ConcurrentHashSet<URL>();

// 失败发起订阅失败的监听器集合
private final ConcurrentMap<URL, Set<NotifyListener>> failedSubscribed = new ConcurrentHashMap<URL, Set<NotifyListener>>();

// 失败取消订阅失败的监听器集合
private final ConcurrentMap<URL, Set<NotifyListener>> failedUnsubscribed = new ConcurrentHashMap<URL, Set<NotifyListener>>();

// 失败通知通知的 URL 集合
private final ConcurrentMap<URL, Map<NotifyListener, List<URL>>> failedNotified = new ConcurrentHashMap<URL, Map<NotifyListener, List<URL>>>();
```

FailbackRegistry 主要就是调用真正子类的方法向注册中心进行发布订阅，如果失败的话会添加到失败的集合，同时每5s会遍历一次失败的集合进行重试，不过理解不了的是如果不管失败成功与失败都会把失败集合中的数据删掉，这时候如果重试又失败了怎么办？


## NotifyListener

```java
public interface NotifyListener {

/**
    * 当收到服务变更通知时触发。
    * <p>
    * 通知需处理契约：<br>
    * 1. 总是以服务接口和数据类型为维度全量通知，即不会通知一个服务的同类型的部分数据，用户不需要对比上一次通知结果。<br>
    * 2. 订阅时的第一次通知，必须是一个服务的所有类型数据的全量通知。<br>
    * 3. 中途变更时，允许不同类型的数据分开通知，比如：providers, consumers, routers, overrides，允许只通知其中一种类型，但该类型的数据必须是全量的，不是增量的。<br>
    * 4. 如果一种类型的数据为空，需通知一个empty协议并带category参数的标识性URL数据。<br>
    * 5. 通知者(即注册中心实现)需保证通知的顺序，比如：单线程推送，队列串行化，带版本对比。<br>
    *
    * @param urls 已注册信息列表，总不为空，含义同{@link com.alibaba.dubbo.registry.RegistryService#lookup(URL)}的返回值。
    */
void notify(List<URL> urls);

}
```


