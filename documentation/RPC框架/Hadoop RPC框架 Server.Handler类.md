# Handler类

Handler类中除了构造函数，只有一个run()函数。

## run()函数

1. 创建ByteArrayOutputStream buf。
2. 从阻塞队列callQueue中获得队首的Call对象。
3. 调用call()函数，返回call()函数和的返回值为Writable类型的value。Server.call()函数是一个抽象函数。
4. 调用Server.setupResponse()函数，创建ByteBuffer类型的response变量，并将其赋值给Call对象的RpcResponse对象。
5. 调用responder.doRespond(call)开始处理Response。

Server.call()函数
Server.setupResponse()函数
response.doResponse()函数

	public void run() {
        ByteArrayOutputStream buf =
            new ByteArrayOutputStream(INITIAL_RESP_BUF_SIZE);
        while (running) {
            TraceScope traceScope = null;
            try {
                final Call call = callQueue.take(); // pop the queue; maybe blocked here
                
                if (!call.connection.channel.isOpen()) {
                    continue;
                }

                String errorClass = null;
                String error = null;
                RpcStatusProto returnStatus = RpcStatusProto.SUCCESS;
                RpcErrorCodeProto detailedErr = null;

                Writable value = null;
				
				// private static final ThreadLocal<Call> CurCall = new ThreadLocal<Call>();
                CurCall.set(call);

                try {
					// 调用call()函数
                    if (call.connection.user == null) {
						// !!!
						// call()函数
                        value = call(call.rpcKind, call.connection.protocolName, call.rpcRequest,
                                     call.timestamp);
                    } else {
                        value =
                            call.connection.user.doAs
                        (new PrivilegedExceptionAction<Writable>() {
                            @Override
                            public Writable run() throws Exception {
                                return call(call.rpcKind, call.connection.protocolName,
                                            call.rpcRequest, call.timestamp);

                            }
                        });
                    }

                } catch (Throwable e) {
                    
                }

                CurCall.set(null);

                synchronized (call.connection.responseQueue) {
                    // setupResponse() needs to be sync'ed together with
                    // responder.doResponse() since setupResponse may use
                    // SASL to encrypt response data and SASL enforces
                    // its own message ordering.

					// !!!
					// ByteArrayOutputStream buf = new ByteArrayOutputStream(INITIAL_RESP_BUF_SIZE);
					// final Call call = callQueue.take();
					// Writable value = call(...)
					// ===> call.setResponse(ByteBuffer.wrap(responseBuf.toByteArray()));
                    setupResponse(buf, call, returnStatus, detailedErr,
                                  value, errorClass, error);

                    // Discard the large buf and reset it back to smaller size
                    // to free up heap
                    if (buf.size() > maxRespSize) {
                        buf = new ByteArrayOutputStream(INITIAL_RESP_BUF_SIZE);
                    }

					// !!!
                    responder.doRespond(call);
                }
            } catch (Exception e) {
                
            } finally {
                
            }
        }
    }