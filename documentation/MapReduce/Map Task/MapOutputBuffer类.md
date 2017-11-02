# MapOutputCollector类

MapOutputCollector是一个抽象类，其主要实现类是MapoutputBuffer。

```
public void init(Context context);
public void collect(K key, V value, int partition);
public void close();
public void flush();
```

MapOutputCollector中还包含一个内部类Context，其中包含以下成员变量。

```
private final MapTask mapTask;
private final JobConf jobConf;
private final TaskReporter reporter;
```

# MapOutputBuffer类

// TO-DO