## Server.Listener类
Server中只有一个Listener线程，负责监听来自Client的SelectionKey.OP_ACCEPT事件，并将接收到的请求Call放入等待队列callQueue中，交由Handler线程处理。

	private ServerSocketChannel acceptChannel = null; 
    private Selector selector = null;
	
	// Reader线程，Listener在接收到Client的OP_ACCEPT事件后，将数据读取工作交由Reader线程处理
    private Reader[] readers = null;	

    private int currentReader = 0;
    private InetSocketAddress address;

### 构造函数

1. 创建ServerSocketChannel acceptChannel，并配置IP地址和端口。
2. 创建Selector。selector = Selector.open();
3. 创建Reader对象数组，并启动每个Reader线程。
4. 为acceptChannel注册OP_ACCEPT事件。

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
            Connection c = connectionManager.register(channel);
            key.attach(c);
            reader.addConnection(c);
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

----------


# Listener类
	
	private class Listener extends Thread

	private ServerSocketChannel acceptChannel = null; //the accept channel
    private Selector selector = null; //the selector that we use for the server
    private Reader[] readers = null;
    private int currentReader = 0;
    private InetSocketAddress address; //the address we bind at
    
## Listener()

Listener构造函数，创建并初始化ServerSocketChannel和Selector。

1. 创建并设置ServerSocketChannel，为ServerSocketChannel绑定IP地址和端口，ServerSocketChannel负责处理OP_ACCEPT事件。
2. 创建Selector。selector = Selector.open();
3. 创建readThreads个Reader线程对象的数组，并启动每个reader线程。
4. 为ServerSocketChannel注册OP_ACCEPT事件。
5. 将Listener对象的线程设为守护线程。

	public Listener() throws IOException {
		address = new InetSocketAddress(bindAddress, port);
		// Create a new server socket and set to non blocking mode
		acceptChannel = ServerSocketChannel.open();
		acceptChannel.configureBlocking(false);
		
		// Bind the server socket to the local host and port
		bind(acceptChannel.socket(), address, backlogLength, conf, portRangeConfig);
		port = acceptChannel.socket().getLocalPort(); //Could be an ephemeral port
	
		// create a selector;
		selector= Selector.open();

		// 创建多个reader线程对象
		readers = new Reader[readThreads];
		for (int i = 0; i < readThreads; i++) {
		    Reader reader = new Reader(
		        "Socket Reader #" + (i + 1) + " for port " + port);
		    readers[i] = reader;
			// 启动reader线程
		    reader.start();
		}
		
		// Register accepts on the server socket with the selector.
		acceptChannel.register(selector, SelectionKey.OP_ACCEPT);

		this.setName("IPC Server listener on " + port);
		this.setDaemon(true);
	}

## Listener.Reader类

	final private BlockingQueue<Connection> pendingConnections;
    private final Selector readSelector;

### Listener.Reader.doRunLoop()

Listener.doAccept() ==> Reader.addConnection(Connection)

Reader.run() ==> Reader.doRunLoop() {Connection conn = pendingConnections.take();}
==> Listener.doRead()

doRunLoop()函数是Reader对象的run()函数的核心实现内容。

1. 从阻塞队列pendingConnections中返回所有的Connection对象，并为Connection对象中channel注册OP_READ事件。
2. 启动选择器。readSelector.select();
3. 将发生的OP_READ事件交由doRead()函数处理。
 
	private synchronized void doRunLoop() {
	    while (running) {
	        SelectionKey key = null;
	        try {
	            // consume as many connections as currently queued to avoid
	            // unbridled acceptance of connections that starves the select
	            int size = pendingConnections.size();
	            for (int i=size; i>0; i--) {
	                Connection conn = pendingConnections.take();
	                conn.channel.register(readSelector, SelectionKey.OP_READ, conn);
	            }
	            readSelector.select();
	
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
	            if (running) {                      // unexpected -- log it
	                LOG.info(Thread.currentThread().getName() + " unexpectedly interrupted", e);
	            }
	        } catch (IOException ex) {
	            LOG.error("Error in Reader", ex);
	        }
	    }
	}

## Listener.Reader.addConnection()函数

	public void addConnection(Connection conn) throws InterruptedException {
        pendingConnections.put(conn);
        readSelector.wakeup();
    }

## Listener.run()

Listener.run()主要用于选择OP_ACCEPT事件。

1. 当running为true时，循环获取Listener对象中的Selector对象，并选择当中的OP_ACCEPT事件，将SelectionKey交由doAccept()处理。
2. 当running为false时，跳出循环，关闭ServerSocketChannel和Selector对象。

	public void run() {
		while (running) {
			SelectionKey key = null;
			try {
                    getSelector().select();
                    Iterator<SelectionKey> iter = getSelector().selectedKeys().iterator();
                    while (iter.hasNext()) {
                        key = iter.next();
                        iter.remove();
                        try {
                            if (key.isValid()) {
                                if (key.isAcceptable())
									// 发生accept事件后，将事件交由doAccept()函数处理
                                    doAccept(key);
                            }
                        } catch (IOException e) {
                        }
                        key = null;
                    }
			} catch (OutOfMemoryError e) {
	
			} catch (Exception e) {

			}
		}
		
		// 关闭Channel和Selector
		synchronized (this) {
            try {
                acceptChannel.close();
                selector.close();
            } catch (IOException e) { }

            selector= null;
            acceptChannel= null;

            // close all connections
            connectionManager.stopIdleScan();
            connectionManager.closeAll();
        }
	}

## Listener.doAccept()函数

doAccept()函数负责处理ServerSocketChannel上发生的OP_ACCEPT事件。doAccept()函数在run()函数中被调用。

1. 从SelectionKey中获得ServerSocketChannel对象。
2. 获得SocketChannel对象，并设置SocketChannel对象。channel = server.accept();
3. 从readers中获得一个Reader。
4. 通过ConnectionManager注册该SocketChannel，创建一个Connection对象，并附加到SelectionKey上。
5. 将该Connection添加到Reader对象中。reader.addConnection(c);

	void doAccept(SelectionKey key) throws InterruptedException, IOException,  OutOfMemoryError {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel channel;
		// 使用while循环的原因???
        while ((channel = server.accept()) != null) {

            channel.configureBlocking(false);
            channel.socket().setTcpNoDelay(tcpNoDelay);
            channel.socket().setKeepAlive(true);
			
			// getReader()从readers数组中获得一个Reader线程
            Reader reader = getReader();
            Connection c = connectionManager.register(channel);
            key.attach(c);  // so closeCurrentConnection can get the object

			// Reader.addConnection()函数将新建的Connection对象加入到BlockingQueue<Connection> pendingConnections中
			// 并且调用readSelector.wakeup()开始等待OP_READ事件
            reader.addConnection(c);
        }
    }

## Listener.doRead()函数

Listener.Reader.doLoopRun() ==> Listener.doRead()

通过readAndProcess()函数处理一个Connection对象上的数据读取过程。

1. 获得Connection。 Connection c = (Connection)key.attachment();
2. 通过Connection.readAndProcess()函数处理读取过程。
3. 关闭连接。closeConnection(c);

    void doRead(SelectionKey key) throws InterruptedException {
        int count = 0;
        Connection c = (Connection)key.attachment();
        if (c == null) {
            return;
        }
        c.setLastContact(Time.now());

        try {
            count = c.readAndProcess();
        } catch (InterruptedException ieo) {
            throw ieo;
        } catch (Exception e) {
            count = -1; //so that the (count < 0) block is executed
        }

        if (count < 0) {
            closeConnection(c);
            c = null;
        } else {
            c.setLastContact(Time.now());
        }
    }
