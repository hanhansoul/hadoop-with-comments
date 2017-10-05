# Responder类

```
private final Selector writeSelector;
private int pending;	// 等待注册的连接
```

```
void decPending()
void incPending()
void waitPending()

// Responder线程run()函数
void run()

// Responder线程run()函数的核心内容
// 调用Responder.doAsyncWrite()和Responder.doPurge()
void doRunLoop()

// doRespond()处理Response，将Call对象加入responseQueue阻塞队列中
void doRespond(Call call)

// 调用processResponse()处理key上的Call对象
void doAsyncWrite(SelectionKey key)

void doPurge(Call call, long now)

// 处理response，每次处理一个Call对象中RpcResponse内容，将其写入通道call.connection.channel中
boolean processResponse(LinkedList<Call> responseQueue, boolean inHandler)
```

```
// 重点
LinkedList<Call> Connection.responseQueue;
Connection.CallQueue;

```

## run()函数

```
public void run() {
	doRunLoop();
}
```
	
## doRunLoop()函数

1. 当running为true时循环运行，当还有未注册的连接时(pending > 0)，阻塞并等待。
2. 当所有连接都已注册，启动writeSelector选择器，设置超时时间为PURGE_INTERVAl，开始选择。Connection.channel在writeSelector注册了OP_WRITE事件。
3. 接收到OP_WRITE事件，由doAsyncWrite(key)处理writeSelector上的事件。
4. 若选择器选择时间没有超过PURGE_INTERVAl，则返回1继续选择器过程。
5. 获得writeSelector中的所有连接Call对象，添加到calls当中。
6. 遍历calls，调用doPurge()处理各个Call对象。

doPurge()
doAsyncWrite()

```
private void doRunLoop() {
    long lastPurgeTime = 0;   // last check for old calls.

    while (running) {
        try {
            waitPending();     // If a channel is being registered, wait.
            writeSelector.select(PURGE_INTERVAL);

            Iterator<SelectionKey> iter = writeSelector.selectedKeys().iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove();
                try {
                    if (key.isValid() && key.isWritable()) {
                        doAsyncWrite(key);
                    }
                } catch (IOException e) {
                }
            }

            long now = Time.now();
            if (now < lastPurgeTime + PURGE_INTERVAL) {
                continue;
            }
            lastPurgeTime = now;

            ArrayList<Call> calls;
            synchronized (writeSelector.keys()) {
                calls = new ArrayList<Call>(writeSelector.keys().size());
                iter = writeSelector.keys().iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    Call call = (Call)key.attachment();
                    if (call != null && key.channel() == call.connection.channel) {
                        calls.add(call);
                    }
                }
            }

            for(Call call : calls) {
                doPurge(call, now);
            }
        } catch (OutOfMemoryError e) {
            try {
                Thread.sleep(60000);
            } catch (Exception ie) {}
        } catch (Exception e) {
            LOG.warn("Exception in Responder", e);
        }
    }
}
```

## doAsyncWrite()函数

1. 从SelectionKey中获取Call对象。
2. 同步call.connection.responseQueue对象，调用processResponse()处理。

```
private void doAsyncWrite(SelectionKey key) throws IOException {
    Call call = (Call)key.attachment();
    if (key.channel() != call.connection.channel) {
        throw new IOException("doAsyncWrite: bad channel");
    }

    synchronized(call.connection.responseQueue) {
        if (processResponse(call.connection.responseQueue, false)) {
            try {
				// 清空在key上注册的事件
                key.interestOps(0);
            } catch (CancelledKeyException e) {
                LOG.warn("Exception while changing ops : " + e);
            }
        }
    }
}
```

## doPurge()函数

Response过程结束，依次关闭超时了的Call对象对应Connection对象。

ConnectionManager.closeConnection()

```
private void doPurge(Call call, long now) {
    LinkedList<Call> responseQueue = call.connection.responseQueue;
    synchronized (responseQueue) {
        Iterator<Call> iter = responseQueue.listIterator(0);
        while (iter.hasNext()) {
            call = iter.next();
            if (now > call.timestamp + PURGE_INTERVAL) {
                closeConnection(call.connection);
                break;
            }
        }
    }
}
```

## doRespond()函数

doRespond()函数将一个Call对象加入到LinkedList队列responseQueue末尾，若当前阻塞队列中只有这一个Call对象，则调用processResponse处理该Response。

调用doRespond()的函数

- Connection.doSaslReplay()
- Connection.processOneRpc()
- Connection.setupBadVersionResponse()
- Connection.setupHttpRequestOnIpcPortResponse()
- Handler.run()


1. 将Call对象添加到call.connection.responseQueue队列的末尾
2. 若call.connection.responseQueue只有一个元素，则直接调用processResponse()处理。

```
//
// Enqueue a response from the application.
//
void doRespond(Call call) throws IOException {
    synchronized (call.connection.responseQueue) {
        call.connection.responseQueue.addLast(call);
        if (call.connection.responseQueue.size() == 1) {
            processResponse(call.connection.responseQueue, true);
        }
    }
}
```

## processResponse()函数

doRespond()函数 / doAsyncWrite()函数 调用了processResponse()函数。

ByteBuffer类型的call.rpcResponse包含了需要发送到客户端的数据。

processResponse()每次只处理responseQueue队列的首个元素。

1. 同步responseQueue，当responseQueue包含0个元素时，数据传输完毕，函数返回true。
2. 移除responseQueue的首个元素call，call = responseQueue.removeFirst()。获得SocketChannel对象call.connection.channel。调用channelWrite()函数将ByteBuffer call.rpcResponse的内容写入channel中，numBytes为写入channel的字节数。
3. 当numBytes < 0时函数返回true。
4. 当call.rpcResponse为空时，即call.rpcResponse.hasRemaining()不为真，将call.rpcResponse置为null，将call.connection的RpcCount减一。如果当前call是responseQueue最后一个元素，则将done置为true，表示没有更多数据写入channel，否则将done置为false。
5. 当call.rpcResponse不为空时，即call.rpcResponse.hasRemaining()为真，则将call重新添加到responseQueue的队首，之后继续处理该call对象的数据传输。
6. 最后关闭call.connection。

```
private boolean processResponse(LinkedList<Call> responseQueue,
                                    boolean inHandler) throws IOException {
	boolean error = true;
    boolean done = false;       // there is more data for this channel.
    int numElements = 0;
    Call call = null;
    try {
        synchronized (responseQueue) {
            numElements = responseQueue.size();
            if (numElements == 0) {
                error = false;
                return true;              // no more data for this channel.
            }

            call = responseQueue.removeFirst();
            SocketChannel channel = call.connection.channel;
            int numBytes = channelWrite(channel, call.rpcResponse);
            if (numBytes < 0) {
                return true;
            }

            if (!call.rpcResponse.hasRemaining()) {
                call.rpcResponse = null;
                call.connection.decRpcCount();
                if (numElements == 1) {    // last call fully processes.
                    done = true;             // no more data for this channel.
                } else {
                    done = false;            // more calls pending to be sent.
                }
            } else {
				// 无法一次性将所有数据都写入通道时
                call.connection.responseQueue.addFirst(call);

                if (inHandler) {
                    incPending();
                    try {
                        // Wakeup the thread blocked on select, only then can the call
                        // to channel.register() complete.
                        writeSelector.wakeup();
                        channel.register(writeSelector, SelectionKey.OP_WRITE, call);
                    } catch (ClosedChannelException e) {
                        done = true;
                    } finally {
                        decPending();
                    }
                }
            }
            error = false;              // everything went off well
        }
    } finally {
        if (error && call != null) {
            done = true;               // error. no more data for this channel.
            closeConnection(call.connection);
        }
    }
    return done;
}
```