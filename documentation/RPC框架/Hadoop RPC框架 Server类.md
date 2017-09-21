## Server

Hadoop采用Master/Slave结构，Master为系统单点，如NameNode或JobTracker。Master通过ipc.Server接收并处理所有Slave发送的请求。Server采用线程池、时间驱动和Reactor设计模式提供并发处理能力。

Reactor模式主要包括以下角色：

- Reactor：IO事件的派发者。
- Accepter：接收来自Client的连接，建立与Client对应的Handler，并向Reactor注册此Handler。
- Handler：与一个Client通信的实体，并按一定的过程实现业务的处理。
- Reader/Sender：为加速处理速度，Reactor模式分离Handler中的读和写两个过程，分别注册成单独的读事件和写事件，分别有对应的Reader和Sender线程处理。

    private String bindAddress;
    private int port;   									// port we listen on
    private int handlerCount;   							// number of handler threads
    private int readThreads;								// number of read threads
    private int readerPendingConnectionQueue; 				// number of connections to queue per read thread
    private Class<? extends Writable> rpcRequestClass;   	// class used for deserializing the rpc request
    final protected RpcMetrics rpcMetrics;
    final protected RpcDetailedMetrics rpcDetailedMetrics;
    
    private Configuration conf;
    private String portRangeConfig = null;
    
    private int maxQueueSize;
    private final int maxRespSize;
    private int socketSendBufferSize;
    private final int maxDataLength;
    private final boolean tcpNoDelay; // if T then disable Nagle's Algorithm
    
    volatile private boolean running = true; 	// true while server runs
    private CallQueueManager<Call> callQueue;	// 共享队列，保存Call对象，执行对应函数调用，由Handler线程完成
    
    // maintains the set of client connections and handles idle timeouts
    private ConnectionManager connectionManager;
    private Listener listener = null;		// Listener线程
    private Responder responder = null;		// Responder线程
    private Handler[] handlers = null;		// Handler线程

### Server中的主要内部类

	private static class Call;
	private class Listener extends Thread;	// 负责监听来自Client的SelectionKey.OP_ACCEPT事件
	private class Responder extends Thread;	// 负责监听Server的SocketChannel上的SelectionKey.OP_WRITE事件
	public class Connection;
	private class Handler extends Thread;	// 负责处理等待队列中Call
	private class ConnectionManager;		// 负责管理Server中的Connection类
	
### 构造函数

	protected Server(String bindAddress, int port,
                     Class<? extends Writable> rpcRequestClass, int handlerCount,
                     int numReaders, int queueSizePerHandler, Configuration conf,
                     String serverName, SecretManager<? extends TokenIdentifier> secretManager,
                     String portRangeConfig) {
		// 为Server类中的变量赋值
		// ...
		listener = new Listener();		// 创建Listener线程对象
        this.port = listener.getAddress().getPort();
        connectionManager = new ConnectionManager();
		// ...
		responder = new Responder();	// 创建Responder线程对象
	}

### setupResponse()函数

Server.Connection ==> setupResponse()
Server.Handler.run() ==> setupResponse()

	private void setupResponse(ByteArrayOutputStream responseBuf,
                               Call call, RpcStatusProto status, RpcErrorCodeProto erCode,
                               Writable rv, String errorClass, String error) {
		
	}

### start()函数和stop()函数

	public synchronized void start() {
        responder.start();		// 启动responder线程
        listener.start();		// 启动listener线程
        handlers = new Handler[handlerCount];	// 创建若干handler线程

		// 启动所有handler线程
        for (int i = 0; i < handlerCount; i++) {
            handlers[i] = new Handler(i);
            handlers[i].start();
        }
    }

	// 停止服务
	// 调用stop()之后，后续的Call调用都不会被处理
	public synchronized void stop() {
        running = false;
		// 中断handler线程
        if (handlers != null) {
            for (int i = 0; i < handlerCount; i++) {
                if (handlers[i] != null) {
                    handlers[i].interrupt();
                }
            }
        }
		// 中断listener线程
        listener.interrupt();
        listener.doStop();
	
		// 中断responder线程
        responder.interrupt();
        
		notifyAll();

        this.rpcMetrics.shutdown();
        this.rpcDetailedMetrics.shutdown();
    }
	
	// TO-DO
	public synchronized void join() throws InterruptedException {
        while (running) {
            wait();
        }
    }

### channelWrite()函数、channelRead()函数、channelIO()函数

分别对WritableByteChannel.write(ByteBuffer)和ReadableByteChannel.read(ByteBuffer)的封装。channelIO()是channelRead()和channelWrite()的辅助函数。
	
	// 当read和write的buffer容量超过NIO_BUFFER_LIMIT时，IO分块大小为NIO_BUFFER_LIMIT。
	private static int NIO_BUFFER_LIMIT = 8*1024;
	// TO-DO

### bind()函数
	// 为Server类绑定地址与端口
	public static void bind(ServerSocket socket, InetSocketAddress address,
                            int backlog, Configuration conf, String rangeConf) {
		//...
		socket.bind(address, backlog);
		//...
	}

### call()函数
	/** Called for each call. */
    public abstract Writable call(RPC.RpcKind rpcKind, String protocol,
                                  Writable param, long receiveTime) throws Exception;

Server.Handler线程中run()将为每一个Call对象调用call()函数。

# Call类

	private final int callId;             // the client's call id
    private final Writable rpcRequest;    // Serialized Rpc request from client
    private final Connection connection;  // connection to client
    private ByteBuffer rpcResponse;       // the response for this call
    private final RPC.RpcKind rpcKind;
    private final byte[] clientId;

	public void setResponse(ByteBuffer response) {
		this.rpcResponse = response;
    }

Call对象保存来自客户端的请求内容，作为Server.CallQueueManager<Call>类型的callQueue队列等待Handler线程处理。

Server.setupResponse()函数调用Call.setResponse()函数，设置Call.rpcResponse。

# Connection类

# Handler类

# Listener类

# Responder类

# ConnectionManager类