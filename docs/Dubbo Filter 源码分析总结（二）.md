# Dubbo Filter 源码分析总结（二）

## TimeoutFilter & DeprecatedFilter & ExceptionFilter

这三个filter比较简单，所以一起总结下

TimeoutFilter 超时过滤器。如果服务调用超时，记录告警日志，不干涉服务的运行。

DeprecatedFilter 废弃调用的过滤器实现类。当调用废弃的服务方法时，打印错误日志提醒。

ExceptionFilter 

和我们平时业务写的用于捕捉异常的过滤器或者拦截器不太一样，而是关注点在服务消费者会不会出现不存在该异常类，导致反序列化的问题。

```java
@Activate(group = Constants.PROVIDER)
public class ExceptionFilter implements Filter {

    private final Logger logger;

    public ExceptionFilter() {
        this(LoggerFactory.getLogger(ExceptionFilter.class));
    }

    public ExceptionFilter(Logger logger) {
        this.logger = logger;
    }

    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        try {
            // 服务调用
            Result result = invoker.invoke(invocation);
            // 有异常，并且非泛化调用
            if (result.hasException() && GenericService.class != invoker.getInterface()) {
                try {
                    Throwable exception = result.getException();

                    // directly throw if it's checked exception
                    // 如果是checked异常，直接抛出
                    if (!(exception instanceof RuntimeException) && (exception instanceof Exception)) {
                        return result;
                    }
                    // directly throw if the exception appears in the signature
                    // 在方法签名上有声明，直接抛出
                    try {
                        Method method = invoker.getInterface().getMethod(invocation.getMethodName(), invocation.getParameterTypes());
                        Class<?>[] exceptionClassses = method.getExceptionTypes();
                        for (Class<?> exceptionClass : exceptionClassses) {
                            if (exception.getClass().equals(exceptionClass)) {
                                return result;
                            }
                        }
                    } catch (NoSuchMethodException e) {
                        return result;
                    }

                    // 未在方法签名上定义的异常，在服务器端打印 ERROR 日志
                    // for the exception not found in method's signature, print ERROR message in server's log.
                    logger.error("Got unchecked and undeclared exception which called by " + RpcContext.getContext().getRemoteHost()
                            + ". service: " + invoker.getInterface().getName() + ", method: " + invocation.getMethodName()
                            + ", exception: " + exception.getClass().getName() + ": " + exception.getMessage(), exception);

                    // 异常类和接口类在同一 jar 包里，直接抛出
                    // directly throw if exception class and interface class are in the same jar file.
                    String serviceFile = ReflectUtils.getCodeBase(invoker.getInterface());
                    String exceptionFile = ReflectUtils.getCodeBase(exception.getClass());
                    if (serviceFile == null || exceptionFile == null || serviceFile.equals(exceptionFile)) {
                        return result;
                    }
                    // 是JDK自带的异常，直接抛出
                    // directly throw if it's JDK exception
                    String className = exception.getClass().getName();
                    if (className.startsWith("java.") || className.startsWith("javax.")) {
                        return result;
                    }
                    // 是Dubbo本身的异常，直接抛出
                    // directly throw if it's dubbo exception
                    if (exception instanceof RpcException) {
                        return result;
                    }

                    // 否则，包装成RuntimeException抛给客户端
                    // otherwise, wrap with RuntimeException and throw back to the client
                    return new RpcResult(new RuntimeException(StringUtils.toString(exception)));
                } catch (Throwable e) {
                    logger.warn("Fail to ExceptionFilter when called by " + RpcContext.getContext().getRemoteHost()
                            + ". service: " + invoker.getInterface().getName() + ", method: " + invocation.getMethodName()
                            + ", exception: " + e.getClass().getName() + ": " + e.getMessage(), e);
                    return result;
                }
            }
            return result;
        } catch (RuntimeException e) {
            logger.error("Got unchecked and undeclared exception which called by " + RpcContext.getContext().getRemoteHost()
                    + ". service: " + invoker.getInterface().getName() + ", method: " + invocation.getMethodName()
                    + ", exception: " + e.getClass().getName() + ": " + e.getMessage(), e);
            throw e;
        }
    }

}
```


## TokenFilter
TokenFilter 用于服务提供者中，提供 令牌验证 的功能。通过令牌验证在注册中心控制权限，以决定要不要下发令牌给消费者，可以防止消费者绕过注册中心访问提供者。

另外通过注册中心可灵活改变授权方式，而不需修改或升级提供者。

流程如下：
1. 【服务消费者】随机 Token

    在 ServiceConfig 的 #doExportUrlsFor1Protocol(protocolConfig, registryURLs) 方法中，随机生成 Token ：

    ```java
    // token ，参见《令牌校验》http://dubbo.apache.org/zh-cn/docs/user/demos/token-authorization.html
    if (!ConfigUtils.isEmpty(token)) {
        if (ConfigUtils.isDefault(token)) { // true || default 时，UUID 随机生成
            map.put("token", UUID.randomUUID().toString());
        } else {
            map.put("token", token);
        }
    }
    ```

2. 【服务消费者】接收 Token

    服务消费者，从注册中心，获取服务提供者的 URL ，从而获得该服务着的 Token 。
所以，即使服务提供者随机生成 Token ，消费者一样可以拿到。

3. 【服务消费者】发送 Token
    RpcInvocation 在创建时，“自动”带上 Token ，如下图所示：

    ```java
    public RpcInvocation(Invocation invocation, Invoker<?> invoker) {
        this(invocation.getMethodName(), invocation.getParameterTypes(),
                invocation.getArguments(), new HashMap<String, String>(invocation.getAttachments()),
                invocation.getInvoker());
        if (invoker != null) {
            URL url = invoker.getUrl();
            setAttachment(Constants.PATH_KEY, url.getPath());
            if (url.hasParameter(Constants.INTERFACE_KEY)) {
                setAttachment(Constants.INTERFACE_KEY, url.getParameter(Constants.INTERFACE_KEY));
            }
            if (url.hasParameter(Constants.GROUP_KEY)) {
                setAttachment(Constants.GROUP_KEY, url.getParameter(Constants.GROUP_KEY));
            }
            if (url.hasParameter(Constants.VERSION_KEY)) {
                setAttachment(Constants.VERSION_KEY, url.getParameter(Constants.VERSION_KEY, "0.0.0"));
            }
            if (url.hasParameter(Constants.TIMEOUT_KEY)) {
                setAttachment(Constants.TIMEOUT_KEY, url.getParameter(Constants.TIMEOUT_KEY));
            }
            // token
            if (url.hasParameter(Constants.TOKEN_KEY)) {
                setAttachment(Constants.TOKEN_KEY, url.getParameter(Constants.TOKEN_KEY));
            }
            if (url.hasParameter(Constants.APPLICATION_KEY)) {
                setAttachment(Constants.APPLICATION_KEY, url.getParameter(Constants.APPLICATION_KEY));
            }
        }
    }
    ```

4. 【服务提供者】认证 Token
    ```java
    /**
    * TokenInvokerFilter
    */
    @Activate(group = Constants.PROVIDER, value = Constants.TOKEN_KEY)
    public class TokenFilter implements Filter {

        public Result invoke(Invoker<?> invoker, Invocation inv)
                throws RpcException {
            // 获得服务提供者配置的 Token 值
            String token = invoker.getUrl().getParameter(Constants.TOKEN_KEY);
            if (ConfigUtils.isNotEmpty(token)) {
                // 从隐式参数中，获得 Token 值。
                Class<?> serviceType = invoker.getInterface();
                Map<String, String> attachments = inv.getAttachments();
                String remoteToken = attachments == null ? null : attachments.get(Constants.TOKEN_KEY);
                // 对比，若不一致，抛出 RpcException 异常
                if (!token.equals(remoteToken)) {
                    throw new RpcException("Invalid token! Forbid invoke remote service " + serviceType + " method " + inv.getMethodName() + "() from consumer " + RpcContext.getContext().getRemoteHost() + " to provider " + RpcContext.getContext().getLocalHost());
                }
            }
            return invoker.invoke(inv);
        }

    }
    ```

## TpsLimitFilter

TpsLimitFilter 用于服务提供者中，提供 限流 的功能。

```java
/**
 * Limit TPS for either service or service's particular method
 */
@Activate(group = Constants.PROVIDER, value = Constants.TPS_LIMIT_RATE_KEY)
public class TpsLimitFilter implements Filter {

    private final TPSLimiter tpsLimiter = new DefaultTPSLimiter();

    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {

        if (!tpsLimiter.isAllowable(invoker.getUrl(), invocation)) {
            throw new RpcException(
                    new StringBuilder(64)
                            .append("Failed to invoke service ")
                            .append(invoker.getInterface().getName())
                            .append(".")
                            .append(invocation.getMethodName())
                            .append(" because exceed max service tps.")
                            .toString());
        }

        return invoker.invoke(invocation);
    }

}

```

### TPSLimiter

```java

public interface TPSLimiter {

    /**
     * judge if the current invocation is allowed by TPS rule
     *
     * 根据 tps 限流规则判断是否限制此次调用.
     *
     * @param url        url
     * @param invocation invocation
     * @return true allow the current invocation, otherwise, return false
     */
    boolean isAllowable(URL url, Invocation invocation);

}

```

### DefaultTPSLimiter
DefaultTPSLimiter 实现 TPSLimiter 接口，默认 TPS 限制器实现类，以服务为维度

```java
public class DefaultTPSLimiter implements TPSLimiter {

    private final ConcurrentMap<String, StatItem> stats
            = new ConcurrentHashMap<String, StatItem>();

    public boolean isAllowable(URL url, Invocation invocation) {
        // 获得 TPS 大小配置项
        int rate = url.getParameter(Constants.TPS_LIMIT_RATE_KEY, -1);
        // 获得 TPS 周期配置项，默认 60 秒
        long interval = url.getParameter(Constants.TPS_LIMIT_INTERVAL_KEY,
                Constants.DEFAULT_TPS_LIMIT_INTERVAL);
        String serviceKey = url.getServiceKey();
        // 要限流
        if (rate > 0) {
            // 获得 StatItem 对象
            StatItem statItem = stats.get(serviceKey);
            // 不存在，则进行创建
            if (statItem == null) {
                stats.putIfAbsent(serviceKey,
                        new StatItem(serviceKey, rate, interval));
                statItem = stats.get(serviceKey);
            }
            // 根据 TPS 限流规则判断是否限制此次调用.
            return statItem.isAllowable();
        } else { // 不限流
            // 移除 StatItem
            StatItem statItem = stats.get(serviceKey);
            if (statItem != null) {
                // 返回通过
                stats.remove(serviceKey);
            }
        }

        return true;
    }

}

class StatItem {

    private String name;

    private long lastResetTime;

    private long interval;

    private AtomicInteger token;

    private int rate;

    StatItem(String name, int rate, long interval) {
        this.name = name;
        this.rate = rate;
        this.interval = interval;
        this.lastResetTime = System.currentTimeMillis();
        this.token = new AtomicInteger(rate);
    }

    public boolean isAllowable() {
        // 若到达下一个周期，恢复可用种子数，设置最后重置时间。
        long now = System.currentTimeMillis();
        if (now > lastResetTime + interval) {
            token.set(rate); // 回复可用种子数
            lastResetTime = now; // 最后重置时间
        }

        // CAS ，直到或得到一个种子，或者没有足够种子
        int value = token.get();
        boolean flag = false;
        while (value > 0 && !flag) {
            flag = token.compareAndSet(value, value - 1);
            value = token.get();
        }

        // 是否成功
        return flag;
    }

    long getLastResetTime() {
        return lastResetTime;
    }

    int getToken() {
        return token.get();
    }

    public String toString() {
        return new StringBuilder(32).append("StatItem ")
                .append("[name=").append(name).append(", ")
                .append("rate = ").append(rate).append(", ")
                .append("interval = ").append(interval).append("]")
                .toString();
    }

}


```