# runNewMapper()函数

1. **MapOutputCollector** ==> **MapOutputBuffer** / DirectMapOutputCollector
2. NewOutputCollector
3. NewTrackingRecordReader
4. **MapContextImpl** / MapContext / Mapper.Context。MapContextImpl实现了TaskInputOutputContextImpl类，TaskInputOutputContextImpl中又包含了一个RecordWriter对象，RecordWriter的实现类是MapOutputBuffer，用于map task的结果输出。

```
public MapContextImpl(Configuration conf, TaskAttemptID taskid,
                      RecordReader<KEYIN,VALUEIN> reader,
                      RecordWriter<KEYOUT,VALUEOUT> writer,
                      OutputCommitter committer,
                      StatusReporter reporter,
                      InputSplit split) {
}
```

1. 获取task的上下文对象taskContext，以创建task所需的相关类。根据taskContext信息，创建Mapper，InputFormat等对象。
2. getSplitDetails()方法获取task对应的分片，根据分片创建RecordReader对象。
3. 创建RecordWriter对象output用于map task结果输出。如果job包含reduce task，则output为NewDirectOutputCollector对象，否则为NewOutputCollector对象。
4. 创建map的上下文对象mapperContext。
5. 初始化input对象。input.initialize(split, mapperContext);
6. 运行mapper。mapper.run()方法会依次将记录的键值对输入到用户定义的map()方法中，执行map任务。mapper.run(mapperContext);
7. 设置task装填，关闭input和output对象。

```
private <INKEY,INVALUE,OUTKEY,OUTVALUE>
	void runNewMapper(final JobConf job,
                      final TaskSplitIndex splitIndex,
                      final TaskUmbilicalProtocol umbilical,
                      TaskReporter reporter
                     ) throws IOException, ClassNotFoundException,
    InterruptedException {

	// 获取task的上下文信息
    org.apache.hadoop.mapreduce.TaskAttemptContext taskContext =
        new org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl(job,
                getTaskID(),
                reporter);

	// 利用反射创建用户定义的Mapper类
    org.apache.hadoop.mapreduce.Mapper<INKEY,INVALUE,OUTKEY,OUTVALUE> mapper =
        (org.apache.hadoop.mapreduce.Mapper<INKEY,INVALUE,OUTKEY,OUTVALUE>)
        ReflectionUtils.newInstance(taskContext.getMapperClass(), job);
	
	// 利用反射创建用户指定的InputFormat实现类
    org.apache.hadoop.mapreduce.InputFormat<INKEY,INVALUE> inputFormat =
        (org.apache.hadoop.mapreduce.InputFormat<INKEY,INVALUE>)
        ReflectionUtils.newInstance(taskContext.getInputFormatClass(), job);
	
	// 创建输入分片
    org.apache.hadoop.mapreduce.InputSplit split = null;
    split = getSplitDetails(new Path(splitIndex.getSplitLocation()),
                            splitIndex.getStartOffset());
	
	// 创建RecordReader实现类
    org.apache.hadoop.mapreduce.RecordReader<INKEY,INVALUE> input =
        new NewTrackingRecordReader<INKEY,INVALUE>
    (split, inputFormat, reporter, taskContext);
    job.setBoolean(JobContext.SKIP_RECORDS, isSkipping());

	// RecordWriter负责将map task的结果以键值对格式输出
    org.apache.hadoop.mapreduce.RecordWriter output = null;

	// 创建task的输出对象
    // get an output object
    if (job.getNumReduceTasks() == 0) {
        output =
            new NewDirectOutputCollector(taskContext, job, umbilical, reporter);
    } else {
        output = new NewOutputCollector(taskContext, job, umbilical, reporter);
    }

	// 创建Mapper的上下文信息
    org.apache.hadoop.mapreduce.MapContext<INKEY, INVALUE, OUTKEY, OUTVALUE>
    mapContext =
        new MapContextImpl<INKEY, INVALUE, OUTKEY, OUTVALUE>(job, getTaskID(),
                input, output,
                committer,
                reporter, split);

    org.apache.hadoop.mapreduce.Mapper<INKEY,INVALUE,OUTKEY,OUTVALUE>.Context
    mapperContext =
        new WrappedMapper<INKEY, INVALUE, OUTKEY, OUTVALUE>().getMapContext(
        mapContext);

    try {
		// 将分片信息加入到mapper的上下文中，使得mapper可以通过nextKeyValue()函数将
		// 键值对传递给map()函数中
        input.initialize(split, mapperContext);
		// 遍历RecordReader对象，依次调用map()函数
        mapper.run(mapperContext);
		
        mapPhase.complete();
		// 设置task状态为SORT
        setPhase(TaskStatus.Phase.SORT);
        statusUpdate(umbilical);
        input.close();
        input = null;
        output.close(mapperContext);
        output = null;
    } finally {
        closeQuietly(input);
        closeQuietly(output, mapperContext);
    }
}
```

---

# NewOutputCollector类

NewOutputCollector类继承自RecordWriter类，用于将map task的结果以键值对形式输出。

NewOutputCollector类中包含一个MapOutputCollector对象collector，其具体实现类是MapOutputBuffer。NewOutputCollector的write()函数就是调用collector的collect()函数，用于将map task的结果输出。

```
// collector为MapOutputBuffer对象
private final MapOutputCollector<K,V> collector;
private final org.apache.hadoop.mapreduce.Partitioner<K,V> partitioner;
private final int partitions;
```

## 构造函数

```
NewOutputCollector(org.apache.hadoop.mapreduce.JobContext jobContext,
                   JobConf job,
                   TaskUmbilicalProtocol umbilical,
                   TaskReporter reporter
                  ) throws IOException, ClassNotFoundException {
	// createSortingCollector默认创建一个新的MapOutputCollector对象
    collector = createSortingCollector(job, reporter);
	// partition的数量与reduce task的数量一致
    partitions = jobContext.getNumReduceTasks();

	// 如果分区数量超过1，创建对应的Partitioner对象
	// 如果分区数量为1，则所有的map task结果都输出到一个分区中
    if (partitions > 1) {
        partitioner = (org.apache.hadoop.mapreduce.Partitioner<K,V>)
                      ReflectionUtils.newInstance(jobContext.getPartitionerClass(), job);
    } else {
        partitioner = new org.apache.hadoop.mapreduce.Partitioner<K,V>() {
            @Override
            public int getPartition(K key, V value, int numPartitions) {
                return partitions - 1;
            }
        };
    }
}
```

## NewOutputCollector.createSortingCollector()函数

利用反射机制，创建并返回一个MapOutputCollector类的实现类MapOutputBuffer对象。

```
private <KEY, VALUE> MapOutputCollector<KEY, VALUE>
    createSortingCollector(JobConf job, TaskReporter reporter)
	throws IOException, ClassNotFoundException {
    MapOutputCollector.Context context =
        new MapOutputCollector.Context(this, job, reporter);

    Class<?>[] collectorClasses = job.getClasses(
                                      JobContext.MAP_OUTPUT_COLLECTOR_CLASS_ATTR, MapOutputBuffer.class);
    int remainingCollectors = collectorClasses.length;
    for (Class clazz : collectorClasses) {
        try {
            if (!MapOutputCollector.class.isAssignableFrom(clazz)) {
                throw new IOException("Invalid output collector class: " + clazz.getName() +
                                      " (does not implement MapOutputCollector)");
            }
            Class<? extends MapOutputCollector> subclazz =
                clazz.asSubclass(MapOutputCollector.class);
			// 创建MapOutputBuffer对象collector
            MapOutputCollector<KEY, VALUE> collector =
                ReflectionUtils.newInstance(subclazz, job);
			// 初始化collector对象，并返回。
            collector.init(context);
            return collector;
        } catch (Exception e) {
            
        }
    }
    throw new IOException("Unable to initialize any output collector");
}
```
