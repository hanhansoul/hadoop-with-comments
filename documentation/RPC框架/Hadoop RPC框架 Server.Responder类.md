# Responder类

	private final Selector writeSelector;
	private int pending;

## run()函数

	public void run() {
		doRunLoop();
    }
	
## doRunLoop()函数

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
	                    LOG.info(Thread.currentThread().getName() + ": doAsyncWrite threw exception " + e);
	                }
	            }
	            long now = Time.now();
	            if (now < lastPurgeTime + PURGE_INTERVAL) {
	                continue;
	            }
	            lastPurgeTime = now;
	            //
	            // If there were some calls that have not been sent out for a
	            // long time, discard them.
	            //
	            if(LOG.isDebugEnabled()) {
	                LOG.debug("Checking for old call responses.");
	            }
	            ArrayList<Call> calls;
	
	            // get the list of channels from list of keys.
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
	            //
	            // we can run out of memory if we have too many threads
	            // log the event and sleep for a minute and give
	            // some thread(s) a chance to finish
	            //
	            LOG.warn("Out of Memory in server select", e);
	            try {
	                Thread.sleep(60000);
	            } catch (Exception ie) {}
	        } catch (Exception e) {
	            LOG.warn("Exception in Responder", e);
	        }
	    }
	}

## doAsyncWrite()函数

## doPurge()函数

## doRespond()函数

doRespond()函数将一个Call对象加入到LinkedList队列responseQueue末尾，若当前阻塞队列中只有这一个Call对象，则调用processResponse处理该Response。

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

## processResponse()函数

doRespond()函数 / doAsyncWrite()函数 调用了processResponse()函数。

ByteBuffer类型的call.rpcResponse包含了需要发送到客户端的数据。

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