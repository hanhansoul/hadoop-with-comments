# Connection类

	private SocketChannel channel;
    private ByteBuffer data;
    private ByteBuffer dataLengthBuffer;
    private LinkedList<Call> responseQueue;
	private Socket socket;
	private String hostAddress;
	private int remotePort;
	private InetAddress addr;
	
## doSaslReply()函数

    private void doSaslReply(Message message) throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Sending sasl message "+message);
        }
        setupResponse(saslResponse, saslCall,
                      RpcStatusProto.SUCCESS, null,
                      new RpcResponseWrapper(message), null, null);
        responder.doRespond(saslCall);
    }

	private void doSaslReply(Exception ioe) throws IOException {
        setupResponse(authFailedResponse, authFailedCall,
                      RpcStatusProto.FATAL, RpcErrorCodeProto.FATAL_UNAUTHORIZED,
                      null, ioe.getClass().getName(), ioe.getLocalizedMessage());
        responder.doRespond(authFailedCall);
    }

**readAndProcess() ==> processOneRpc() ==> processRpcRequest() / processRpcOutputOfBandRequest()**

## Connection.readAndProcess()函数

Listener.doRead() ==> Connection.readAndProcess()

readAndProcess()函数负责从Channel中读取序列化的数据，然后将其反序列化，再将反序列化后的数据交给processOneRpc(byte[])处理。

	public int readAndProcess() throws WrappedRpcServerException, IOException, InterruptedException {
        while (true) {
            /* Read at most one RPC. If the header is not read completely yet
             * then iterate until we read first RPC or until there is no data left.
             */
            int count = -1;
            if (dataLengthBuffer.remaining() > 0) {
                count = channelRead(channel, dataLengthBuffer);
                if (count < 0 || dataLengthBuffer.remaining() > 0)
                    return count;
            }

            if (!connectionHeaderRead) {
                //Every connection is expected to send the header.
                if (connectionHeaderBuf == null) {
                    connectionHeaderBuf = ByteBuffer.allocate(3);
                }
                count = channelRead(channel, connectionHeaderBuf);
                if (count < 0 || connectionHeaderBuf.remaining() > 0) {
                    return count;
                }
                int version = connectionHeaderBuf.get(0);
                // TODO we should add handler for service class later
                this.setServiceClass(connectionHeaderBuf.get(1));
                dataLengthBuffer.flip();

                // Check if it looks like the user is hitting an IPC port
                // with an HTTP GET - this is a common error, so we can
                // send back a simple string indicating as much.
                if (HTTP_GET_BYTES.equals(dataLengthBuffer)) {
                    setupHttpRequestOnIpcPortResponse();
                    return -1;
                }

                if (!RpcConstants.HEADER.equals(dataLengthBuffer)
                    || version != CURRENT_VERSION) {
                    //Warning is ok since this is not supposed to happen.
                    LOG.warn("Incorrect header or version mismatch from " +
                             hostAddress + ":" + remotePort +
                             " got version " + version +
                             " expected version " + CURRENT_VERSION);
                    setupBadVersionResponse(version);
                    return -1;
                }

                // this may switch us into SIMPLE
                authProtocol = initializeAuthContext(connectionHeaderBuf.get(2));

                dataLengthBuffer.clear();
                connectionHeaderBuf = null;
                connectionHeaderRead = true;
                continue;
            }

            if (data == null) {
                dataLengthBuffer.flip();
                dataLength = dataLengthBuffer.getInt();
                checkDataLength(dataLength);
                data = ByteBuffer.allocate(dataLength);
            }

            count = channelRead(channel, data);

            if (data.remaining() == 0) {
                dataLengthBuffer.clear();
                data.flip();
                boolean isHeaderRead = connectionContextRead;
                processOneRpc(data.array());
                data = null;
                if (!isHeaderRead) {
                    continue;
                }
            }
            return count;
        }
    }

## Connection.processOneRpc()函数

## Connection.processRpcOutOfBandRequest()函数

## Connection.processRpcRequest()函数

