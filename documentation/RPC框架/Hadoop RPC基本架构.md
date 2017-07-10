### Hadoop RPC 基本框架分析

#### 4.3.1 RPC基本概念

RPC是一种通过网络从远程计算机上请求服务，但不需要了解底层网络技术的协议。RPC协议是以封装某些传输协议如TCP或UDP等，
并通过这些传输协议为通信程序之间传递访问请求或者应答信息。

RPC通常采用客户机/服务器模型。请求程序是一个客户机，而服务提供程序则是一个服务器。一个典型RPC框架主要包括以下部分。
1. 通信模块

    两个互相写作的通信模块实现请求——应答协议。它们在客户机和服务器之间传递请求和应答消息，一般不会对数据包进行任何处理。

    应答协议有两种实现方式，同步方式和异步方式。
2. Stub程序

    客户端和服务器端均包含Stub程序，可将之看做代理程序。它使得远程函数调用表现的跟本地调用一样，对用户程序完全透明。

    在客户端，它表现的就像一个本地程序，但不直接执行本地调用，而是将请求信息通过网络模块发送给服务器端。

    Stub程序将依次解码请求消息参数、调用响应的服务过程和编码应答结果的返回值。
3. 调度程序

    调度程序接收到来自通信模块的请求消息，并根据其中的标识选择一个Stub程序处理。采用线程池可以提高大并发请求情况下的处理效率。
4. 客户程序/服务过程

    请求的发出者和请求的处理者。

RPC请求过程
1. 客户程序以本地方式调用系统产生的Stub程序。

2. 该Stub程序将函数调用信息按照网络通信模块的要求封装成消息包，并交给通信模块发送给远程服务器端。

3. 远程服务器端接收此消息后，将此消息发送给相应的Stub程序。

4. Stub程序拆封消息，形成被调用过程要求的形式，并调用对应的函数。

5. 被调用函数按照所获得的参数执行，并将结果返回给Stub程序。

6. Stub程序将此结果封装成消息，通过网络通信模块逐级地传送给客户程序。

#### 4.3.2 Hadoop RPC基本框架

##### 1. Hadoop RPC使用

Hadoop RPC主要对外提供了两种接口

1. public static VersionedProtocol getProxy/waitForProxy()

    构造一个客户端代理对象，用于向服务器端发送RPC请求。

2. public static Server getServer()

    为某个协议，即一个Java接口，实例构造一个服务器对象，用于处理客户端发送的请求。

Hadoop RPC使用步骤

1. 定义RPC协议。RPC协议是客户端和服务器端之间的通信接口，定义了服务器端对外提供的服务接口。定义一个ClientProtocol通信
接口，声明两个方法：echo()和add()。Hadoop所有自定义RPC接口必须继承VersionedProtocol接口，它描述了协议的版本信息。

2. 实现RPC协议。Hadoop RPC协议通常是一个Java接口，用户需要实现该接口。
  ```
  public static class ClientProtocolImpl implements ClientProtocol {
    public long getProtocolVersion(String protocol, long clientVersion) {
      return ClientProtocol.versionID;
    }
    public String echo(String value) throws IOException {
      return value;
    }
    public int add(int v1, int v2) throws IOException {
      return v1 + v2;
    }
  }
  ```

3. 构造并启动RPC server。直接使用静态方法getServer()构造一个RPC server，调用函数start()启动该Server。
serverHost和serverPort分别表示服务器的host和监听端口号，而numHandlers表示服务器端处理请求的线程数目。
到此为止，服务器处理监听状态，等待客户端请求到达。
  ```
  server = RPC.getServer(new ClientProtocolImpl(), serverHost,
    serverPort, numHandlers, false, conf);
  server.start();
  ```

4. 构造RPC Client，并发送RPC请求。使用静态方法getProxy()构造客户端代理对象，直接通过代理对象调用远程端的方法。
  ```
  proxy = (ClientProtocol)RPC.getProxy(ClientProtocol.class,
    ClientProtocol.versionID, addr, conf);
  int resutl = proxy.add(5, 6);
  String echoResult = proxy.echo("result");
  ```

Hadoop RPC只要由三个大类组成，分别是RPC、Client和Server，分别对应对外编程接口、客户端实现和服务器端实现。

#### 2.ipc.RPC类分析

RPC类是对底层客户端/服务器网络模型的封装，以便为程序员提供一套更方便简介的编程接口。

RPC类自定义了一个内部类RPC.Server，它继承了Server抽象类，并利用Java反射机制实现了call接口，即根据客户端请
求中的调用方法名称和对应参数完成方法调用。RPC类包含一个ClientCache类型的成员变量，根据用户提供的SocketFactory
缓存Client对象，以达到重用Client对象的目的。

Hadoop RPC使用Java动态代理完成对远程方法的调用。对于Hadoop RPC，函数调用由客户端发出，并在服务器端执行并返
回，因此不能像本地动态代理一样直接在invoke()方法中本地调用相关函数，它的做法是，在invoke()方法中，将函数调用
信息打包成可序列化的Invocation对象，并通过网路发送给服务器端，服务器端收到该调用信息后，解析出函数名和函数参数
列表等信息，利用Java反射机制完成函数调用。

#### 3.ipc.Client类分析

Client主要完成发送远程过程调用信息并接收执行结果。Client类对外提供了两种接口，一种用于执行单个远程调用，另外
一种用于执行批量远程调用。
```
public Writable call(Writable param, ConnectionId remotrId)
    throws InterruptedException, IOException;
public Writable[] call(Writable[] params, InetSocketAddress[] addresses,
    Class<?> protocol, UserGroupInformation ticket, Configuration conf)
    throws IOException, InterruptedException;
```

Client内部有两个内部类，分别是Call和Connection。

Call类：该类封装了一个RPC请求，包含五个成员变量，分别是唯一标识id、函数调用信息param、函数执行返回值value、出错
或异常信息error和执行完成标识符done。由于Hadoop RPC Server采用异步方式处理客户端请求，这使得远程过程调
用的发生顺序与结果返回顺序无直接关系，而Client端正是通过id识别不同的函数调用。当客户端向服务器端发送请求
时，只需填充id和param两个变量，而剩下的三个变量：value，error和done，则由服务器端根据函数执行情况填充。

Connection类：Client和每个Server之间维护一个通信连接。该连接相关的基本信息及操作都封装在Connection类中。其中，
基本信息主要包括：通信连接唯一标识remoteId，与Server端通信的Socket类socket，网络输入数据流in，网络输出数据流out，
保存RPC请求的哈希表calls等
- addCall：将一个Call对象添加到哈希表中；
- sendParam：像服务器端发送RPC请求；
- receiveResponse：从服务器端接收已经处理完成的RPC请求；
- run：Connection是一个线程类，它的run()方法调用了receiveResponse()方法，一直等待接收RPC返回结果。

调用call()函数执行某个远程方法时，Client端需要进行以下步骤：

1. 创建一个Connection对象，并将远程方法调用信息封装成Call对象，放到Connection对象中的哈希表calls中；

2. 调用Connection类中的sendParam()方法将当前Call对象发送给Server端；

3. Server端处理完RPC请求后，将结果通过网络返回给Client端，Client端通过receiveResponse()函数获取结果；

4. Client端检查结果处理状态，并将对应的Call对象从哈希表中删除。


#### 4.ipc.Server类分析

Hadoop采用Master/Slave结构。Master是整个系统的单点，如NameNode或JobTracker，通过ipc.Server接收并
处理所有Slave发送的请求，这就要求ipc.Server将高并发和可扩展性作为设计目标。因此，ipc.Server采用了诸多
提高并发处理能力的计数，如线程池、事件驱动和Reactor设计模式等。

##### Reactor设计模式

Reactor设计模式是并发编程中一种基于事件驱动的设计模式，具有以下两个特点：
1. 通过派发/分离I/O操作事件提高系统的并发性能；
2. 提供了粗粒度的并发控制，使用单线程实现，避免复杂的同步处理。

##### Reactor实现原理

Reactor模式主要包括以下几个角色
- Reactor：I/O事件的派发者；

- Acceptor：接收来自Client的连接，简历与Client对应的Handler，并向Reactor注册此Handler；

- Handler：与一个Client通信的实体，并按一定的过程实现业务的处理。Handler进一步划分以抽象如read、
decode、compute、encode和send等过程。Reactor模式总，业务逻辑被分散的I/O事件所打破，所以Handler
需要处理在所需信息不全的时候保存上下文，并在下一次I/O事件到来的时候继续上一次中断的操作。

- Reader/Sender：为了加速处理速度，Reactor模式往往构建一个存放数据处理线程的线程池，数据独处后，立即
放入线程池中等待后续处理。Reactor一般分离Handler中读和写两个过程，分别注册成单独的读事件和写事件，并
由对应的Reader和Sender线程处理。

##### ipc.Server实现细节

ipc.Server主要功能是接收来自客户端的RPC请求，经过调用相应的函数获取结果后，返回给对应的客户端。ipc.Server
被划分为三个阶段：接收请求、处理请求和返回结果。

**1. 接收请求**

该阶段主要任务是接收来自各个客户端的RPC请求，并将其封装成固定的格式Call对象，放入共享队列callQueue中，以便
进行后续的处理。该阶段内部又分为两个自阶段：建立连接和接收请求，分别由Listener和Reader线程完成。

整个Server只有一个Listener线程，同一负责监听来自客户端的连接请求。一旦有新的请求达到，它会采用轮训的方式从线
程池中选择一个Reader线程进行处理。而Reader线程可同时存在多个，它们分别负责接收一部分客户端连接的RPC请求。
Reader线程将决定Reader线程的分配机制。

Listener和Reader线程内部各自包含一个Selector对象，分别用于监听SelectionKey.OP_ACCEPT和SelectionKey.OP_READ
事件。对于Listener线程，主循环的实现体是监听是否有新的连接请求到达，并采用轮训策略选择一个Reader线程处
理新的连接；对于Reader线程，主循环的实现体是监听其负责的客户端连接中是否有新的RPC请求到达，并将新的RPC
请求封装成Call对象，放入共享队列callQueue中。

**2. 处理请求**

该阶段的主要任务侍从共享队列callQueue中获取Call对象，执行对应的函数调用，并将结果返回给客户端，这是由
Handler线程完成的。

Server端可同时存在多个Handler线程。它们并行从共享队列总读取Call对象，经执行对应的函数调用后，将尝试着
直接将结果返回给对应的客户端。Handler将尝试将后续发送的任务交给Responder线程。

**3. 返回结果**

每个Handler线程执行完函数调用后，会尝试着将执行结果返回给客户端，但由于函数调用返回的结果往往超过网络带
宽，因此发送任务将交给Responder线程。

Server端近存在一个Responder线程。它的内部包含一个Selector对象，用于监听SelectionKey.OP_WRITE事件
。当Handler没能够将结果一次性发送给客户端时，会向该Selector对象注册SelectionKey.OP_WRITE事件，进而
由Responder线程采用异步方式继续发送未发送完成的结果。
