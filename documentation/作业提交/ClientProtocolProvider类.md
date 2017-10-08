# ClientProtocolProvider

## LocalClientProtocolProvider

### LocalClientProtocolProvider.create()函数 

1. conf.get()获取framework。
2. 如果framework值为local，设置map任务数量为1，否则返回null。
3. 返回LocalJobRunner。

```
public ClientProtocol create(Configuration conf) throws IOException {
    String framework =
        conf.get(MRConfig.FRAMEWORK_NAME, MRConfig.LOCAL_FRAMEWORK_NAME);
    if (!MRConfig.LOCAL_FRAMEWORK_NAME.equals(framework)) {
        return null;
    }
    conf.setInt(JobContext.NUM_MAPS, 1);

    return new LocalJobRunner(conf);
}
```

### LocalJobRunner

## YarnClientProtocolProvider

### YarnClientProtocolProvider.create()

```
public ClientProtocol create(Configuration conf) throws IOException {
    if (MRConfig.YARN_FRAMEWORK_NAME.equals(conf.get(MRConfig.FRAMEWORK_NAME))) {
        return new YARNRunner(conf);
    }
    return null;
}
```

### YARNRunner