# Cluster类

Cluster类提供了连接MapReduce集群的通信方式，可以远程获取MapReduce集群。

抽象类ClientProtocolProvider有两个派生类，YarnClientProtocolProvider为YARN模式，LocalClientProtocolProvider为本地模式。

```
public static enum JobTrackerStatus {INITIALIZING, RUNNING};

//客户端通信协议提供者
private ClientProtocolProvider clientProtocolProvider;

// 客户端通信协议实例
private ClientProtocol client;

// 用户信息
// UserGroupInformation用于提供JAAS验证
private UserGroupInformation ugi;

// hadoop.conf.Configuration
private Configuration conf;

// hadoop.fs.FileSystem
private FileSystem fs = null;

// 系统路径
private Path sysDir = null;
// 作业资源存放路径
private Path stagingAreaDir = null;
// 作业历史路径
private Path jobHistoryDir = null;

// 客户端通信协议提供者加载器
private static ServiceLoader<ClientProtocolProvider> frameworkLoader =
        ServiceLoader.load(ClientProtocolProvider.class);
```

## 构造函数

初始化conf和ugi变量，调用initialize()函数初始化。

```
public Cluster(InetSocketAddress jobTrackAddr, Configuration conf)
throws IOException {
    this.conf = conf;
    this.ugi = UserGroupInformation.getCurrentUser();
    initialize(jobTrackAddr, conf);
}
```

## initialize()函数

initialize()主要功能是通过遍历frameworkLoader中的ClientProtocolProvider类创建ClientProtocol对象，初始化ClientProtocolProvider和client变量。Client为提交器，分为本地提交器和YARN提交器，具体选择由配置文件决定。Client实例用于与ResourceManager通信。

// TO-DO

ClientProtocol接口是VersionProtocol的子类，ClientProtocol类是JobClient和JobTracker用于通信的协议。JobClient类使用ClientProtocol类方法提交任务，获取当前系统状态。

java.util.ServiceLoader<ClientProtocolProvider> frameworkLoader。ServiceLoader???

抽象类ClientProtocolProvider提供create()函数，用于创建ClientProtocol类。


```
private void initialize(InetSocketAddress jobTrackAddr, Configuration conf)
    throws IOException {

    synchronized (frameworkLoader) {
        for (ClientProtocolProvider provider : frameworkLoader) {
            ClientProtocol clientProtocol = null;
            try {
                if (jobTrackAddr == null) {
                    clientProtocol = provider.create(conf);
                } else {
                    clientProtocol = provider.create(jobTrackAddr, conf);
                }

                if (clientProtocol != null) {
                    clientProtocolProvider = provider;
                    client = clientProtocol;
                    break;
                } else {
                    // return null protocol
                }
            } catch (Exception e) {

            }
        }
    }

    if (null == clientProtocolProvider || null == client) {
        // error
    }
}
```