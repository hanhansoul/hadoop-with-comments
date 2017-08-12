## Server.Listener类
Server中只有一个Listener线程，负责监听来自Client的SelectionKey.OP_ACCEPT事件，并将接收到的请求Call放入等待队列callQueue中，交由Handler线程处理。

	private ServerSocketChannel acceptChannel = null; 
    private Selector selector = null;
	
	// Reader线程，Listener在接收到Client的OP_ACCEPT事件后，将数据读取工作交由Reader线程处理
    private Reader[] readers = null;	

    private int currentReader = 0;
    private InetSocketAddress address;

### 构造函数

	public Listener() throws IOException {
		// 创建ServerSocketChannel对象
		address = new InetSocketAddress(bindAddress, port);
		acceptChannel = ServerSocketChannel.open();
		acceptChannel.configureBlocking(false);
		
		bind(acceptChannel.socket(), address, backlogLength, conf, portRangeConfig);
		port = acceptChannel.socket().getLocalPort(); //Could be an ephemeral port
		
		// 创建Selector
		selector= Selector.open();
		
		// 创建Reader读取线程
		readers = new Reader[readThreads];
		for (int i = 0; i < readThreads; i++) {
		    Reader reader = new Reader(
		        "Socket Reader #" + (i + 1) + " for port " + port);
		    readers[i] = reader;
		    reader.start();
		}
		
		// 在selector上注册OP_ACCEPT事件
		acceptChannel.register(selector, SelectionKey.OP_ACCEPT);
		this.setName("IPC Server listener on " + port);
		this.setDaemon(true);
	}

### Listener.run()函数
Server.run() ==> Listener.run()
Listener线程中，ServerSocketChannel对象acceptChannel在Listener构造函数中注册了OP_ACCEPT事件。
1. 当running为true时，进入循环。
2. getSelector().select()获取选择器，并开始select过程。
3. select()返回，获取返回的SelectionKey中的OP_ACCEPT事件，调用doAccept(key)处理该事件。
4. 关闭acceptChannel和selector，重新回到第一步，检查并进入循环。

	public void run() {
		// ...
		connectionManager.startIdleScan();	// ???
		// ...
		while(running) {
			SelectionKey key = null;
			try {
				// getSelector()返回Listener的selector
				// 监听OP_ACCEPT事件
				getSelector().select();

				// doAccept(key)函数处理OP_ACCEPT事件
				Iterator<SelectionKey> iter = getSelector().selectedKeys().iterator();
				while (iter.hasNext()) {
					key = iter.next();
					iter.remove();
					try {
						if (key.isValid()) {
							if (key.isAcceptable())
								doAccept(key);
						}
					} catch (IOException e) {
					}
					key = null;
				}

			} catch (OutOfMemoryError e) {
				// ...
			}

			// 关闭acceptChannel、selector
			// ...
		}
	}

### Listener.doAccept()函数

Listener.run() ==> Listener.doAccept()
doAccept()在从ServerSocketChannel获取SocketChannel后，获取一个Connection对象，并将Connection对象添加到一个Reader对象中的pendingConnections中，之后唤醒Reader对象中的Selector。

	void doAccept(SelectionKey key) throws InterruptedException, IOException,  OutOfMemoryError {
		// 从SelectionKey中获取ServerSocketChannel对象
        ServerSocketChannel server = (ServerSocketChannel) key.channel();

		// 再由ServerSocketChannel.accept()对象获取SocketChannel对象
        SocketChannel channel;
        while ((channel = server.accept()) != null) {
			// 设置SocketChannel对象参数
			// ...
			// TO-DO
            Reader reader = getReader();	// 从Listener.readers[]中获取一个Reader对象
            Connection c = connectionManager.register(channel);		// ???
            key.attach(c);
            reader.addConnection(c);	// ???
        }
    }

### Listener.doRead()函数
Reader.doRunLoop() ==> Listener.doRead()

	void doRead(SelectionKey key) throws InterruptedException {
		// ...
		Connection c = (Connection)key.attachment();
		// ...

	}
		
### Listener.doStop()函数

	synchronized void doStop() {
            if (selector != null) {
                selector.wakeup();	// ???
                Thread.yield();		// ???
            }

			// 关闭acceptChannel
            if (acceptChannel != null) {
				acceptChannel.socket().close();
            }

			// 结束每一个Reader线程
            for (Reader r : readers) {
                r.shutdown();
            }
        }

### closeCurrentConnection()函数

----------

## Listener.Reader类

	private class Reader Extends Thread {
		
		final private BlockingQueue<Connection> pendingConnections;		// TO-DO
		private final Selector readSelector;		// 线程中的选择器 ???
		// ...
	}

### Reader.run()函数
Server.start() ==> Reader.run()
Reader.run()函数调用doRunLoop()，启动了对OP_ACCEPT事件的监听

	private void run() {
		// ...
		doRunLoop();
		// ...
	}


### Reader.doRunLoop()函数
Reader.run() ==> Reader.doRunLoop()
1. 当running为true时，进入循环，开始监听acceptChannel上的OP_ACCEPT事件
2.  

	private synchronized void doRunLoop() {
		while (running) {
		    SelectionKey key = null;
		    try {
		        int size = pendingConnections.size();
				// TO-DO
		        for (int i=size; i>0; i--) {
		            Connection conn = pendingConnections.take();
		            conn.channel.register(readSelector, SelectionKey.OP_READ, conn);
		        }
				
				// 监听OP_ACCEPT事件
		        readSelector.select();
				
				// 处理OP_READ事件
		        Iterator<SelectionKey> iter = readSelector.selectedKeys().iterator();
		        while (iter.hasNext()) {
		            key = iter.next();
		            iter.remove();
		            if (key.isValid()) {
		                if (key.isReadable()) {
		                    doRead(key);
		                }
		            }
		            key = null;
		        }
		    } catch (InterruptedException e) {
		        // ...
		    } catch (IOException ex) {
		        // ...
		    }
		}
	}
	
### Reader.addConnection()函数
	
	public void addConnection(Connection conn) throws InterruptedException {
		pendingConnections.put(conn);
		readSelector.wakeup();	// ???
	}
	
### Reader.shutdown()函数
	void shutdown() {
		readSelector.wakeup();
	}
	 