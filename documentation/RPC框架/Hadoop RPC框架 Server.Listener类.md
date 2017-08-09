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

	public void run() {
		// ...
		connectionManager.startIdleScan();
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
		}
	}

### doAccept()函数

### doRead()函数

### doStop()函数

### closeCurrentConnection()函数

----------

## Listener.Reader类

	private class Reader Extends Thread {
		
		final private BlockingQueue<Connection> pendingConnections;		// TO-DO
		private final Selector readSelector;		// 线程中的选择器 ???

		public void run() {
            LOG.info("Starting " + Thread.currentThread().getName());
            try {
                doRunLoop();		// 
            } finally {
                try {
                    readSelector.close();
                } catch (IOException ioe) {
                    LOG.error("Error closing read selector in " + Thread.currentThread().getName(), ioe);
                }
            }
        }

		// ...
	}

### Reader.doRunLoop()函数
	
	private synchronized void doRunLoop() {
		while (running) {
		    SelectionKey key = null;
		    try {
		        // consume as many connections as currently queued to avoid
		        // unbridled acceptance of connections that starves the select
		        int size = pendingConnections.size();
				// TO-DO
		        for (int i=size; i>0; i--) {
		            Connection conn = pendingConnections.take();
		            conn.channel.register(readSelector, SelectionKey.OP_READ, conn);
		        }
				
				// 监听OP_ACCEPT事件
		        readSelector.select();
				
				// 处理
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
		readSelector.wakeup();
	}
	
### Reader.shutdown()函数
	void shutdown() {
		readSelector.wakeup();
	}
	 