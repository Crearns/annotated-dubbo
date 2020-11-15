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