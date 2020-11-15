# Dubbo 服务调用之本地调用源码分析总结

Dubbo 本地调用主要采用的 Injvm 协议，并且没有通信部分，实现较为简单


## 消费者调用

* 消费者调用服务的顺序图
<a href="https://sm.ms/image/EraLFX6l4vBUpTt" target="_blank"><img src="https://i.loli.net/2020/09/13/EraLFX6l4vBUpTt.jpg" ></a>

### Proxy
图中 1、2、3 主要是动态代理的部分，当 ProxyFactory 返回代理对象，调用代理方法后，会通过反射调用 invoker 的方法

### ProtocolFilterWrapper
图中 5 的部分，ProtocolFilterWrapper 的带有过滤链的 Invoker，主要是调用 buildInvokerChain 形成的filter链
```java
for (int i = filters.size() - 1; i >= 0; i--) {
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
```

#invoke(invocation) 方法中，调用 Filter#(invoker, invocation) 方法，不断执行过滤逻辑。而在 Filter 中，又不断调用 Invoker#invoker(invocation) 方法，最终最后一个 Filter ，会调用 InjvmInvoker#invoke(invocation) 方法，继续执行逻辑。


### ListenerInvokerWrapper

ListenerInvokerWrapper 类，主要目的是为了 InvokerListener 的触发，目前该监听器只有 #referred(invoker) #destroyed(invoker) 两个接口方法，并未对 #invoke(invocation) 的过程，实现监听。因此，ListenerInvokerWrapper 的 #invoke(invocation) 的实现基本等于零.

### AbstractInvoker
对应图中 7 的部分，AbstractInvoker，在 #invoke(invocation) 方法中，实现了公用逻辑，同时抽象了 #doInvoke(invocation) 方法，子类实现自定义逻辑。

```java
public Result invoke(Invocation inv) throws RpcException {
    if (destroyed.get()) {
        throw new RpcException("Rpc invoker for service " + this + " on consumer " + NetUtils.getLocalHost()
                + " use dubbo version " + Version.getVersion()
                + " is DESTROYED, can not be invoked any more!");
    }
    RpcInvocation invocation = (RpcInvocation) inv;
    // 设置 `invoker` 属性
    invocation.setInvoker(this);
    // 添加公用的隐式传参，例如，`path` `interface` 等等，详见 RpcInvocation 类。
    if (attachment != null && attachment.size() > 0) {
        invocation.addAttachmentsIfAbsent(attachment);
    }
    // 添加自定义的隐式传参
    Map<String, String> context = RpcContext.getContext().getAttachments();
    if (context != null) {
        invocation.addAttachmentsIfAbsent(context);
    }
    // 设置 `async=true` ，若为异步方法
    if (getUrl().getMethodParameter(invocation.getMethodName(), Constants.ASYNC_KEY, false)) {
        invocation.setAttachment(Constants.ASYNC_KEY, Boolean.TRUE.toString());
    }
    RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);


    // 执行调用
    try {
        return doInvoke(invocation);

    } catch (InvocationTargetException e) { // biz exception
        Throwable te = e.getTargetException();
        if (te == null) {
            return new RpcResult(e);
        } else {
            if (te instanceof RpcException) {
                ((RpcException) te).setCode(RpcException.BIZ_EXCEPTION);
            }
            return new RpcResult(te);
        }
    } catch (RpcException e) {
        if (e.isBiz()) {
            return new RpcResult(e);
        } else {
            throw e;
        }
    } catch (Throwable e) {
        return new RpcResult(e);
    }
}
```

RpcContext 是一个临时状态记录器，当接收到 RPC 请求，或发起 RPC 请求时，RpcContext 的状态都会变化。
比如：A 调 B，B 再调 C，则 B 机器上，在 B 调 C 之前，RpcContext 记录的是 A 调 B 的信息，在 B 调 C 之后，RpcContext 记录的是 B 调 C 的信息。

### InjvmInvoker

```java
/**
    * Exporter 集合
    *
    * key: 服务键
    *
    * 该值实际就是 {@link com.alibaba.dubbo.rpc.protocol.AbstractProtocol#exporterMap}
    */
public Result doInvoke(Invocation invocation) throws Throwable {
    // 获得 Exporter 对象
    Exporter<?> exporter = InjvmProtocol.getExporter(exporterMap, getUrl());
    if (exporter == null) {
        throw new RpcException("Service [" + key + "] not found.");
    }
    // 设置服务提供者地址为本地
    RpcContext.getContext().setRemoteAddress(NetUtils.LOCALHOST, 0);
    // 调用
    return exporter.getInvoker().invoke(invocation);
}
```


## 提供者调用

* 提供者提供服务的顺序图
<a href="https://sm.ms/image/u5gmjD6oipYMbNn" target="_blank"><img src="https://i.loli.net/2020/09/13/u5gmjD6oipYMbNn.jpg" ></a>

### InjvmInvoker

对应图中 [1] [2]

### ProtocolFilterWrapper

对应图中 [3] [4]，与消费者相同

### DelegateProviderMetaDataInvoker

对应图中 [5]

```java
public class DelegateProviderMetaDataInvoker<T> implements Invoker {
    protected final Invoker<T> invoker;
    private ServiceConfig metadata;

    public DelegateProviderMetaDataInvoker(Invoker<T> invoker,ServiceConfig metadata) {
        this.invoker = invoker;
        this.metadata = metadata;
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

    public void destroy() {
        invoker.destroy();
    }

    public ServiceConfig getMetadata() {
        return metadata;
    }
}
```

### Wrapper
对应图中 [6] [7] [8] [9]
