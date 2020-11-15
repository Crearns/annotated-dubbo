# Dubbo Filter 源码分析总结（一）
## ContextFilter

### RPCContext
RpcContext 是一个 ThreadLocal 的临时状态记录器，当接收到 RPC 请求，或发起 RPC 请求时，RpcContext 的状态都会变化。比如：A 调 B，B 再调 C，则 B 机器上，在 B 调 C 之前，RpcContext 记录的是 A 调 B 的信息，在 B 调 C 之后，RpcContext 记录的是 B 调 C 的信息。

#### 服务消费方
```java
// 远程调用
xxxService.xxx();
// 本端是否为消费端，这里会返回true
boolean isConsumerSide = RpcContext.getContext().isConsumerSide();
// 获取最后一次调用的提供方IP地址
String serverIP = RpcContext.getContext().getRemoteHost();
// 获取当前服务配置信息，所有配置信息都将转换为URL的参数
String application = RpcContext.getContext().getUrl().getParameter("application");
// 注意：每发起RPC调用，上下文状态会变化
yyyService.yyy();
```


#### 服务提供方
```java
public class XxxServiceImpl implements XxxService {
    public void xxx() {
        // 本端是否为提供端，这里会返回true
        boolean isProviderSide = RpcContext.getContext().isProviderSide();
        // 获取调用方IP地址
        String clientIP = RpcContext.getContext().getRemoteHost();
        // 获取当前服务配置信息，所有配置信息都将转换为URL的参数
        String application = RpcContext.getContext().getUrl().getParameter("application");
        // 注意：每发起RPC调用，上下文状态会变化
        yyyService.yyy();
        // 此时本端变成消费端，这里会返回false
        boolean isProviderSide = RpcContext.getContext().isProviderSide();
    } 
}
```


#### 代码如下
```java
/**
 * RpcContext 线程变量
 */
private static final ThreadLocal<RpcContext> LOCAL = new ThreadLocal<RpcContext>() {

    @Override
    protected RpcContext initialValue() {
        return new RpcContext();
    }

};

/**
 * 隐式参数集合
 */
private final Map<String, String> attachments = new HashMap<String, String>();
// 实际未使用
private final Map<String, Object> values = new HashMap<String, Object>();
/**
 * 异步调用 Future
 */
private Future<?> future;
/**
 * 可调用服务的 URL 对象集合
 */
private List<URL> urls;
/**
 * 调用服务的 URL 对象
 */
private URL url;
/**
 * 方法名
 */
private String methodName;
/**
 * 参数类型数组
 */
private Class<?>[] parameterTypes;
/**
 * 参数值数组
 */
private Object[] arguments;
/**
 * 服务消费者地址
 */
private InetSocketAddress localAddress;
/**
 * 服务提供者地址
 */
private InetSocketAddress remoteAddress;

@Deprecated // DUBBO-325 废弃的，使用 urls 属性替代
private List<Invoker<?>> invokers;
@Deprecated // DUBBO-325 废弃的，使用 url 属性替代
private Invoker<?> invoker;
@Deprecated // DUBBO-325 废弃的，使用 methodName、parameterTypes、arguments 属性替代
private Invocation invocation;

/**
 * 请求
 *
 * 例如，在 RestProtocol
 */
private Object request;
/**
 * 响应
 *
 * 例如，在 RestProtocol
 */
private Object response;
 
 // ... 省略一些
 ```

 ### ConsumerContextFilter
 ```java
 /**
 * ConsumerContextInvokerFilter
 */
@Activate(group = Constants.CONSUMER, order = -10000)
public class ConsumerContextFilter implements Filter {

    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 设置 RpcContext 对象
        RpcContext.getContext()
                .setInvoker(invoker)
                .setInvocation(invocation)
                .setLocalAddress(NetUtils.getLocalHost(), 0) // 本地地址
                .setRemoteAddress(invoker.getUrl().getHost(), // 远程地址
                        invoker.getUrl().getPort());
        // 设置 RpcInvocation 对象的 `invoker` 属性
        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(invoker);
        }
        // 服务调用
        try {
            return invoker.invoke(invocation);
        } finally {
            // 清理隐式参数集合
            RpcContext.getContext().clearAttachments();
        }
    }

}
```


### ContextFilter
```java
/**
 * ContextInvokerFilter
 */
@Activate(group = Constants.PROVIDER, order = -10000)
public class ContextFilter implements Filter {

    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 创建新的 `attachments` 集合，清理公用的隐式参数
        Map<String, String> attachments = invocation.getAttachments();
        if (attachments != null) {
            attachments = new HashMap<String, String>(attachments);
            attachments.remove(Constants.PATH_KEY);
            attachments.remove(Constants.GROUP_KEY);
            attachments.remove(Constants.VERSION_KEY);
            attachments.remove(Constants.DUBBO_VERSION_KEY);
            attachments.remove(Constants.TOKEN_KEY);
            attachments.remove(Constants.TIMEOUT_KEY);
            attachments.remove(Constants.ASYNC_KEY);// Remove async property to avoid being passed to the following invoke chain.
            // 清空消费端的异步参数
        }
        // 设置 RpcContext 对象
        RpcContext.getContext()
                .setInvoker(invoker)
                .setInvocation(invocation)
//                .setAttachments(attachments)  // merged from dubbox
                .setLocalAddress(invoker.getUrl().getHost(),
                        invoker.getUrl().getPort());

        // mreged from dubbox
        // we may already added some attachments into RpcContext before this filter (e.g. in rest protocol)
        // 在此过滤器(例如rest协议)之前，我们可能已经在RpcContext中添加了一些附件。
        if (attachments != null) {
            if (RpcContext.getContext().getAttachments() != null) {
                RpcContext.getContext().getAttachments().putAll(attachments);
            } else {
                RpcContext.getContext().setAttachments(attachments);
            }
        }

        // 设置 RpcInvocation 对象的 `invoker` 属性
        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(invoker);
        }
        try {
            // 服务调用
            return invoker.invoke(invocation);
        } finally {
            // 移除上下文
            RpcContext.removeContext();
        }
    }
}
```


## AccessLogFilter
com.alibaba.dubbo.rpc.filter.AccessLogFilter ，实现 Filter 接口，记录服务的访问日志的过滤器实现类。

```java
/**
 * 访问日志在 {@link LoggerFactory} 中的日志名
 */
private static final String ACCESS_LOG_KEY = "dubbo.accesslog";

/**
 * 访问日志的文件后缀
 */
private static final String FILE_DATE_FORMAT = "yyyyMMdd";
/**
 * 日历的时间格式化
 */
private static final String MESSAGE_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
/**
 * 队列大小，即 {@link #logQueue} 值的大小
 */
private static final int LOG_MAX_BUFFER = 5000;
/**
 * 日志输出频率，单位：毫秒。仅适用于 {@link #logFuture}
 */
private static final long LOG_OUTPUT_INTERVAL = 5000;

/**
 * 日志队列
 *
 * key：访问日志名
 * value：日志集合
 */
private final ConcurrentMap<String, Set<String>> logQueue = new ConcurrentHashMap<String, Set<String>>();
/**
 * 定时任务线程池
 */
private final ScheduledExecutorService logScheduled = Executors.newScheduledThreadPool(2, new NamedThreadFactory("Dubbo-Access-Log", true));
/**
 * 记录日志任务
 */
private volatile ScheduledFuture<?> logFuture = null;
```

```java
public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
    try {
        // 记录访问日志的文件名
        String accesslog = invoker.getUrl().getParameter(Constants.ACCESS_LOG_KEY);
        if (ConfigUtils.isNotEmpty(accesslog)) {
            // 服务的名字、版本、分组信息
            RpcContext context = RpcContext.getContext();
            String serviceName = invoker.getInterface().getName();
            String version = invoker.getUrl().getParameter(Constants.VERSION_KEY);
            String group = invoker.getUrl().getParameter(Constants.GROUP_KEY);
            // 拼接日志内容
            StringBuilder sn = new StringBuilder();
            sn.append("[").append(new SimpleDateFormat(MESSAGE_DATE_FORMAT).format(new Date())).append("] ").append(context.getRemoteHost()).append(":").append(context.getRemotePort())
                    .append(" -> ").append(context.getLocalHost()).append(":").append(context.getLocalPort())
                    .append(" - ");
            if (null != group && group.length() > 0) { // 分组
                sn.append(group).append("/");
            }
            sn.append(serviceName); // 服务名
            if (null != version && version.length() > 0) { // 版本
                sn.append(":").append(version);
            }
            sn.append(" ");
            sn.append(inv.getMethodName()); // 方法名
            sn.append("(");
            Class<?>[] types = inv.getParameterTypes();  // 参数类型
            if (types != null && types.length > 0) {
                boolean first = true;
                for (Class<?> type : types) {
                    if (first) {
                        first = false;
                    } else {
                        sn.append(",");
                    }
                    sn.append(type.getName());
                }
            }
            sn.append(") ");
            Object[] args = inv.getArguments(); // 参数值
            if (args != null && args.length > 0) {
                sn.append(JSON.toJSONString(args));
            }
            String msg = sn.toString();
            if (ConfigUtils.isDefault(accesslog)) {
                // 【方式一】使用日志组件，例如 Log4j 等写
                LoggerFactory.getLogger(ACCESS_LOG_KEY + "." + invoker.getInterface().getName()).info(msg);
            } else {
                // 【方式二】异步输出到指定文件
                log(accesslog, msg);
            }
        }
    } catch (Throwable t) {
        logger.warn("Exception in AcessLogFilter of service(" + invoker + " -> " + inv + ")", t);
    }
    return invoker.invoke(inv);
}

/**
 * 初始化任务
 */
private void init() {
    if (logFuture == null) {
        synchronized (logScheduled) {
            if (logFuture == null) { // 双重锁，避免重复初始化
                logFuture = logScheduled.scheduleWithFixedDelay(new LogTask(), LOG_OUTPUT_INTERVAL, LOG_OUTPUT_INTERVAL, TimeUnit.MILLISECONDS);
            }
        }
    }
}

/**
 * 添加日志内容到日志队列
 *
 * @param accesslog 日志文件
 * @param logmessage 日志内容
 */
private void log(String accesslog, String logmessage) {
    // 初始化
    init();
    // 获得队列，以文件名为 Key
    Set<String> logSet = logQueue.get(accesslog);
    if (logSet == null) {
        logQueue.putIfAbsent(accesslog, new ConcurrentHashSet<String>());
        logSet = logQueue.get(accesslog);
    }
    // 若未超过队列大小，添加到队列中
    if (logSet.size() < LOG_MAX_BUFFER) {
        logSet.add(logmessage);
    }
}
```

## ActiveLimitFilter && ExecuteLimitFilter
服务方法的最大可并行调用的限制过滤器，在服务消费者和提供者各有一个 LimitFilter 


### RPCStatus

RPC 状态。可以计入如下维度统计

```java
/**
 * 基于服务 URL 为维度的 RpcStatus 集合
 *
 * key：URL
 */
private static final ConcurrentMap<String, RpcStatus> SERVICE_STATISTICS = new ConcurrentHashMap<String, RpcStatus>();
/**
 * 基于服务 URL + 方法维度的 RpcStatus 集合
 *
 * key1：URL
 * key2：方法名
 */
private static final ConcurrentMap<String, ConcurrentMap<String, RpcStatus>> METHOD_STATISTICS = new ConcurrentHashMap<String, ConcurrentMap<String, RpcStatus>>();

// 目前没有用到
private final ConcurrentMap<String, Object> values = new ConcurrentHashMap<String, Object>();
/**
 * 调用中的次数
 */
private final AtomicInteger active = new AtomicInteger();
/**
 * 总调用次数
 */
private final AtomicLong total = new AtomicLong();
/**
 * 总调用失败次数
 */
private final AtomicInteger failed = new AtomicInteger();
/**
 * 总调用时长，单位：毫秒
 */
private final AtomicLong totalElapsed = new AtomicLong();
/**
 * 总调用失败时长，单位：毫秒
 */
private final AtomicLong failedElapsed = new AtomicLong();
/**
 * 最大调用时长，单位：毫秒
 */
private final AtomicLong maxElapsed = new AtomicLong();
/**
 * 最大调用失败时长，单位：毫秒
 */
private final AtomicLong failedMaxElapsed = new AtomicLong();
/**
 * 最大调用成功时长，单位：毫秒
 */
private final AtomicLong succeededMaxElapsed = new AtomicLong();

/**
 * Semaphore used to control concurrency limit set by `executes`
 *
 * 服务执行信号量，在 {@link com.alibaba.dubbo.rpc.filter.ExecuteLimitFilter} 中使用
 */
private volatile Semaphore executesLimit;
/**
 * 服务执行信号量大小
 */
private volatile int executesPermits;
```

```java
/**
 * 服务调用开始的计数
 *
 * @param url URL 对象
 * @param methodName 方法名
 */
public static void beginCount(URL url, String methodName) {
    // `SERVICE_STATISTICS` 的计数
    beginCount(getStatus(url));
    // `METHOD_STATISTICS` 的计数
    beginCount(getStatus(url, methodName));
}

private static void beginCount(RpcStatus status) {
    // 调用中的次数
    status.active.incrementAndGet();
}

/**
* 服务调用结束的计数
*
* @param url URL 对象
* @param elapsed 时长，毫秒
* @param succeeded 是否成功
*/
public static void endCount(URL url, String methodName, long elapsed, boolean succeeded) {
    // `SERVICE_STATISTICS` 的计数
    endCount(getStatus(url), elapsed, succeeded);
    // `METHOD_STATISTICS` 的计数
    endCount(getStatus(url, methodName), elapsed, succeeded);
}

private static void endCount(RpcStatus status, long elapsed, boolean succeeded) {
    // 次数计数
    status.active.decrementAndGet();
    status.total.incrementAndGet();
    status.totalElapsed.addAndGet(elapsed);
    // 时长计数
    if (status.maxElapsed.get() < elapsed) {
        status.maxElapsed.set(elapsed);
    }
    if (succeeded) {
        if (status.succeededMaxElapsed.get() < elapsed) {
            status.succeededMaxElapsed.set(elapsed);
        }
    } else {
        status.failed.incrementAndGet(); // 失败次数
        status.failedElapsed.addAndGet(elapsed);
        if (status.failedMaxElapsed.get() < elapsed) {
            status.failedMaxElapsed.set(elapsed);
        }
    }
}

public Semaphore getSemaphore(int maxThreadNum) {
    if(maxThreadNum <= 0) {
        return null;
    }
    // 若信号量不存在，或者信号量大小改变，创建新的信号量
    if (executesLimit == null || executesPermits != maxThreadNum) {
        synchronized (this) {
            if (executesLimit == null || executesPermits != maxThreadNum) {
                executesLimit = new Semaphore(maxThreadNum);
                executesPermits = maxThreadNum;
            }
        }
    }
    // 返回信号量
    return executesLimit;
}
```

### ActiveLimitFilter

```java
/**
 * LimitInvokerFilter
 */
@Activate(group = Constants.CONSUMER, value = Constants.ACTIVES_KEY)
public class ActiveLimitFilter implements Filter {

    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        URL url = invoker.getUrl();
        String methodName = invocation.getMethodName();
        // 获得服务提供者每服务每方法最大可并行执行请求数
        int max = invoker.getUrl().getMethodParameter(methodName, Constants.ACTIVES_KEY, 0);
        // 获得 RpcStatus 对象，基于服务 URL + 方法维度
        RpcStatus count = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());
        if (max > 0) {
            // 获得超时值
            long timeout = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, 0);
            long start = System.currentTimeMillis();
            long remain = timeout; // 剩余可等待时间
            int active = count.getActive();
            // 超过最大可并行执行请求数，等待
            if (active >= max) { // 用了等待-通知模型
                synchronized (count) { // 通过锁，有且仅有一个在等待。
                    // 循环，等待可并行执行请求数
                    while ((active = count.getActive()) >= max) {
                        // 等待，直到超时，或者被唤醒
                        try {
                            count.wait(remain);
                        } catch (InterruptedException e) {
                        }
                        // 判断是否没有剩余时长了，抛出 RpcException 异常
                        long elapsed = System.currentTimeMillis() - start; // 本地等待时长
                        remain = timeout - elapsed;
                        if (remain <= 0) {
                            throw new RpcException("Waiting concurrent invoke timeout in client-side for service:  "
                                    + invoker.getInterface().getName() + ", method: "
                                    + invocation.getMethodName() + ", elapsed: " + elapsed
                                    + ", timeout: " + timeout + ". concurrent invokes: " + active
                                    + ". max concurrent invoke limit: " + max);
                        }
                    }
                }
            }
        }
        try {
            long begin = System.currentTimeMillis();
            // 调用开始的计数
            RpcStatus.beginCount(url, methodName);
            try {
                // 服务调用
                Result result = invoker.invoke(invocation);
                // 调用结束的计数（成功）
                RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, true);
                return result;
            } catch (RuntimeException t) {
                // 调用结束的计数（失败）
                RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, false);
                throw t;
            }
        } finally {
            // 唤醒等待的相同服务的相同方法的请求
            if (max > 0) {
                synchronized (count) {
                    count.notify();
                }
            }
        }
    }

}
```

### ExecuteLimitFilter
```java
/**
 * ThreadLimitInvokerFilter
 */
@Activate(group = Constants.PROVIDER, value = Constants.EXECUTES_KEY)
public class ExecuteLimitFilter implements Filter {

    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        URL url = invoker.getUrl();
        String methodName = invocation.getMethodName();
        Semaphore executesLimit = null; // 信号量
        boolean acquireResult = false; // 是否获得信号量
        // 获得服务提供者每服务每方法最大可并行执行请求数
        int max = url.getMethodParameter(methodName, Constants.EXECUTES_KEY, 0);
        if (max > 0) {
            // 获得 RpcStatus 对象，基于服务 URL + 方法维度
            RpcStatus count = RpcStatus.getStatus(url, invocation.getMethodName());
            // 获得信号量。若获得不到，抛出异常。
//            if (count.getActive() >= max) {
            /**
             * http://manzhizhen.iteye.com/blog/2386408
             * use semaphore for concurrency control (to limit thread number)
             */
            executesLimit = count.getSemaphore(max);
            if(executesLimit != null && !(acquireResult = executesLimit.tryAcquire())) {
                throw new RpcException("Failed to invoke method " + invocation.getMethodName() + " in provider " + url + ", cause: The service using threads greater than <dubbo:service executes=\"" + max + "\" /> limited.");
            }
        }
        long begin = System.currentTimeMillis();
        boolean isSuccess = true;
        // 调用开始的计数
        RpcStatus.beginCount(url, methodName);
        try {
            // 服务调用
            Result result = invoker.invoke(invocation);
            return result;
        } catch (Throwable t) {
            isSuccess = false;
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else {
                throw new RpcException("unexpected exception when ExecuteLimitFilter", t);
            }
        } finally {
            // 调用结束的计数（成功）（失败）
            RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, isSuccess);
            // 释放信号量
            if(acquireResult) {
                executesLimit.release();
            }
        }
    }

}
```