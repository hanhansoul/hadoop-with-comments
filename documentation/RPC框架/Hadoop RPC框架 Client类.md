## Client

Client主要用于发送远程过程调用信息并接收执行结果。

**Client.call()为Client的入口函数。**

**WritableRpcEngine，ProtobufRpcEngine中声明了Client类型变量**


Client.call() { call = new Call(rpcKind, rpcRequest); }
==> Client.getConnetion(remoteId, call, serviceClass, fallbackToSimpleAuth) {
        call被封装在Connection中。
        getConnection()是从连接池中获取一个连接的。
        调用Client.Connection.setupIOstreams()。
    }
==> Client.Connection.setupIOstreams() {启动Connection的线程run()}
==> Client.Connection.run()
                                        
                                        
==> Client.Connection.sendRpcRequest(call) 
==> call.wait() 
==> call.getRpcResponse()

Client.Connection.run() ==> Client.Connection.receiveRpcResponse() {RpcEngine}
```
// Client包含的内部类

public static class ConnectionId

private static class ClientExecutorServiceFactory

// 主要内部类
static class Call

// 主要内部类
private class Connection extends Thread

// valueClass指定Client端call封装的Writable rpcResponse类型
private Class<? extends Writable> valueClass;

// 用于在Client.Connection中创建socket连接
private SocketFactory socketFactory;           // how to create sockets

// 保存连接Connection的连接池
private Hashtable<ConnectionId, Connection> connections =
        new Hashtable<ConnectionId, Connection>();

```

```
// 核心方法
// 
// Make a call, passing rpcRequest, to the IPC server defined by
// remoteId, returning the rpc response.
//
public Writable call(RPC.RpcKind rpcKind, Writable rpcRequest,
                         ConnectionId remoteId, int serviceClass,
                         AtomicBoolean fallbackToSimpleAuth) throws IOException {
                             
    // 创建一个新的RPC请求封装，指定RpcEngine。
    final Call call = createCall(rpcKind, rpcRequest);
	// 创建一个新的RPC连接封装
    Connection connection = getConnection(remoteId, call, serviceClass, 
            fallbackToSimpleAuth);
    
    ...
    // 发送rpc请求
    connection.sendRpcRequest(call);
    ...
    // 等待完成，call.done为true
    call.wait();
    ...
    // call没有被中断，也没有抛出异常，返回响应值call.rpcResponse。
    return call.getRpcResponse();
}
                         

```

```
// 与Client.call()相关

// 从连接线程池中取出一个连接或创建一个新连接加入线程池中，并返回。

// Connection connection;
// connection.setupIOstreams(fallbackToSimpleAuth);
// return Client.Connection;

Connection getConnection(ConnectionId remoteId, Call call, 
    int serviceClass, AtomicBoolean fallbackToSimpleAuth) {
    Connection connection;
    // 创建连接
    // 从连接池中取出连接或将心连接加入连接池中
    do {
        synchronized (connections) {
            connection = connections.get(remoteId);
            if (connection == null) {
                connection = new Connection(remoteId, serviceClass);
                connections.put(remoteId, connection);
            }
        }
    } while (!connection.addCall(call));
    
    // 建立连接，新建IO流，发送header至服务器端
    connection.setupIOstreams(fallbackToSimpleAuth);
    return connection;
}

```

### Client.Call类

~~Call类封装了一个RPC请求，包含五个成员变量，唯一标识id、函数调用信息param、函数执行返回值value、异常信息error和执行完成标识符done。RPC服务器采用异步方式处理客户端请求，远程过程调用的发生顺序与结果返回顺序无直接关系，而Client是通过id识别不同的函数调用。当向客户端发送请求时，只需要填充id和param两个变量，其余变量则由服务器端根据函数执行请求填充。~~

Call类封装了一个RPC请求。包括唯一标识id，

```
static class Call {
    final int id;                   // call id
    final Writable rpcRequest;      // the serialized rpc request
    Writable rpcResponse;           // null if rpc has error
    final RPC.RpcKind rpcKind;      // Rpc EngineKind
    
    // 连接池
    private Hashtable<ConnectionId, Connection> connections =
        new Hashtable<ConnectionId, Connection>();
    ...
    
    public synchronized void setRpcResponse(Writable rpcResponse);
    
    // 返回服务器端响应值
    public synchronized Writable getRpcResponse();
}

```

### Client.Connection类

```
private class Connection extends Thread {
    // currently active calls
    private Hashtable<Integer, Call> calls = new Hashtable<Integer, Call>();
    
    // socket连接
    private Socket socket = null;
    // 输入输出流
    private DataInputStream in;
    private DataOutputStream out;
}
```

```
private synchronized boolean waitForWork() {
    // 等待，直到接收到signal指示开始读取RPC响应或超时、连接关闭
    // 在calls不为空，且连接未断开的情况下，返回true。
}

@override
public void run() {
    try {
        // 等待，直到RPC响应可读或连接中断
        while (waitForWork()) {
            //wait here for work - read or close connection
            // Connection.receiveRpcResponse(); 将从out中读取的内容写入对应的call中
            receiveRpcResponse();
        }
    } catch (Throwable t) {
        // ...
    }

    // 关闭连接connection
    close();
}
```

```
// 在Client.call()中被调用

// 将call序列化后，通过Client.Connection发送给服务器端
// Format of a call on the wire:
// 0) Length of rest below (1 + 2)
// 1) RpcRequestHeader  - is serialized Delimited hence contains length
// 2) RpcRequest

/**
    final DataOutputBuffer d = new DataOutputBuffer();
    // 将call.rpcRequest中的内容写入d中。
    call.rpcRequest.write(d)
*/

public void sendRpcRequest(final Call call) throws InterruptedException, IOException {
    // 创建输出流缓存
    final DataOutputBuffer d = new DataOutputBuffer();
    // 创建一个数据表头header
    RpcRequestHeaderProto header = ProtoUtil.makeRpcRequestHeader(
        call.rpcKind, OperationProto.RPC_FINAL_PACKET, 
        call.id, call.retry,
        clientId);
    // 将call.rpcRequest中的内容写入d中。
    call.rpcRequest.write(d);
    
    Future<?> senderFuture = sendParamsExecutor.submit(new Runnable() {
        @Override
        public void run() {
            // ...
            synchronized (Connection.this.out)
                // ...
                // DataOutputStream Connection.out;
                byte[] data = d.getData();
                int totalLength = d.getLength();
                out.writeInt(totalLength);          // Total Length
                out.write(data, 0, totalLength);    // RpcRequestHeader + RpcRequest
                out.flush();
            }
        }
    }
    
    //
    senderFuture.get();
}
```

```
/**
    receiveRpcResponse()运行在run()中，从out中读取数据，并将数据写入相应的Client.call中。
*/
private void receiveRpcResponse() {
    // DataInputStream Connection.in;
    
    int totalLen = in.readInt();
    
    // 从服务器端返回的响应中获取对应的call标识，并通过这个标识从calls中获取call
    
    RpcResponseHeaderProto header =
        RpcResponseHeaderProto.parseDelimitedFrom(in);
    int callId = header.getCallId();
    Call call = calls.get(callId);
    
    // RPC请求返回的状态
    RpcStatusProto status = header.getStatus();
    if (status == RpcStatusProto.SUCCESS) {
        // 请求返回成功
        // 获取从服务器端返回的response，并赋值给call.rpcResponse
        Writable value = ReflectionUtils.newInstance(valueClass, conf);
        value.readFields(in);                 // read value
        
        calls.remove(callId);
        call.setRpcResponse(value);
        
        // 验证响应的长度是否正确
        // 只对ProtobufEngine类型进行验证
        if (call.getRpcResponse() instanceof ProtobufRpcEngine.RpcWrapper) {
            // ...
        }
    } else {
        // 处理请求返回失败情况
    }
}
```

```
/**
    为Connection创建socket连接。
    主要目的是通过socketFactory.createSocket()创建Connection.socket，并对其bind和connection。
*/
private synchronized void setupConnection() throws IOException {
    while (true) {
        try {
            this.socket = socketFactory.createSocket();
            this.socket.setTcpNoDelay(tcpNoDelay);
            this.socket.setKeepAlive(true);
            
            // bind
            if (ticket != null && ticket.hasKerberosCredentials()) {
                KerberosInfo krbInfo =
                    remoteId.getProtocol().getAnnotation(KerberosInfo.class);
                if (krbInfo != null && krbInfo.clientPrincipal() != null) {
                    InetAddress localAddr = NetUtils.getLocalInetAddress(host);
                    if (localAddr != null) {
                        this.socket.bind(new InetSocketAddress(localAddr, 0));
                    }
                }
            }
            
            // 连接
            NetUtils.connect(this.socket, server, connectionTimeout);
            // 设置超时
            this.socket.setSoTimeout(pingInterval);
        } catch () {
            // ...
        }
    }
}
```

```
/**
    private DataInputStream in;
    private DataOutputStream out;
    setupIOstreams的主要目的是为Connection.in，Connection.out赋值。
    并启动Connection中的主线程Connection.run()
*/
private synchronized void setupIOstreams(AtomicBoolean fallbackToSimpleAuth) {
    try {
        while (true) {
            // 创建socket连接
            setupConnection();
            // 使用创建的socket创建输入输出流
            InputStream inStream = NetUtils.getInputStream(socket);
            OutputStream outStream = NetUtils.getOutputStream(socket);
            // 在连接第一次创建时，发送一个header信息
            writeConnectionHeader(outStream);
            
            // 与安全认证相关???
            if (authProtocol == AuthProtocol.SASL) {
                // ...   
            }
            if (authMethod != AuthMethod.SIMPLE) {
                // ...
            } else if (UserGroupInformation.isSecurityEnabled()) {
                
            }
        
            // 为in赋值
            this.in = new DataInputStream(new BufferedInputStream(inStream));
            
            // 为out赋值
            this.out = new DataOutputStream(outStream);
            
            /* Write the connection context header for each connection
             * Out is not synchronized because only the first thread does this.
            */
            writeConnectionContext(remoteId, authMethod);
         
            // 启动Connection中的线程run()
            start();
        }
    }
}
```