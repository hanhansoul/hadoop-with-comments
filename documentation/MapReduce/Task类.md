# Task类

```
private String jobFile;                         // 作业配置文件
private String user;                            // 作业用户
private TaskAttemptID taskId;                   // unique, includes job id
private int partition;                          // 该task在当前job中的索引号
TaskStatus taskStatus;                          // 当前task的状态
protected JobStatus.State jobRunStateForCleanup;
protected boolean jobCleanup = false;			// 标志位
protected boolean jobSetup = false;				// 标志位
protected boolean taskCleanup = false;			// 标志位

private SortedRanges skipRanges = new SortedRanges();
private boolean skipping = false;
private boolean writeSkipRecs = true;

//currently processing record start index
private volatile long currentRecStartIndex;
private Iterator<Long> currentRecIndexIterator =
    skipRanges.skipRangeIterator();

private ResourceCalculatorProcessTree pTree;
private long initCpuCumulativeTime = 0;

protected JobConf conf;
protected MapOutputFile mapOutputFile;
protected LocalDirAllocator lDirAlloc;
private final static int MAX_RETRIES = 10;
protected JobContext jobContext;
protected TaskAttemptContext taskContext;
protected org.apache.hadoop.mapreduce.OutputFormat<?,?> outputFormat;
protected org.apache.hadoop.mapreduce.OutputCommitter committer;
```

## TaskReporter类

## run()函数

抽象类，在Task的子类MapTask和ReduceTask中被实现。

```
public abstract void run(JobConf job, TaskUmbilicalProtocol umbilical)
    throws IOException, ClassNotFoundException, InterruptedException;
```

## reportNextRecordRange()函数

## startReporter()函数

## updateResourceCounters()函数

## GcTimeUpdater类

## FileSystemStatisticUpdater类

```
private Map<String, FileSystemStatisticUpdater> statisticUpdaters =
    new HashMap<String, FileSystemStatisticUpdater>();
```

## write()函数 / readFields()函数

## initialize()函数

MapTask.run() / ReduceTask.run()将调用initialize()函数

1. 获取job和task的上下文信息jobContext和taskContext。
2. 创建OutputCommitter对象，用于将结果输出到HDFS。
3. 获取job输出路径，并为FileOutputFormat设置工作路径。
4. committer.setupTask(taskContext)。
5. 利用工厂方法，创建ResourceCalculatorProcessTree的对象，ResourceCalculatorProcessTree是YARN中用于获取进程树中所有进程运行状态的类。


```
// 1
// JobContext是一个job的只读视图，提供给运行中task读取
// TaskAttemptContext提供map task和reduce task的上下文信息
protected JobContext jobContext;
protected TaskAttemptContext taskContext;
···
jobContext = new JobContextImpl(job, id, reporter);
taskContext = new TaskAttemptContextImpl(job, taskId, reporter);

// 2
protected org.apache.hadoop.mapreduce.OutputFormat<?,?> outputFormat;
protected org.apache.hadoop.mapreduce.OutputCommitter committer;
···
outputFormat = ReflectionUtils.newInstance(taskContext.getOutputFormatClass(), job);
committer = outputFormat.getOutputCommitter(taskContext);

/*
1. 初始化job，例如为job创建临时输出目录。
2. 在job结束后负责清理job，例如删除job的临时输出目录。
3. 为task创建临时输出目录。
4. 根据task是否需要commit操作，负责提交task的输出或舍弃task的输出
*/
```

```
public void initialize(JobConf job, JobID id,
                           Reporter reporter,
                           boolean useNewApi) throws IOException,
                                       ClassNotFoundException, InterruptedException {
    jobContext = new JobContextImpl(job, id, reporter);
    taskContext = new TaskAttemptContextImpl(job, taskId, reporter);

    if (getState() == TaskStatus.State.UNASSIGNED) {
        setState(TaskStatus.State.RUNNING);
    }

	// 初始化committer
	// committer负责将task的输出commit到指定位置
    if (useNewApi) {
        outputFormat =
            ReflectionUtils.newInstance(taskContext.getOutputFormatClass(), job);
        committer = outputFormat.getOutputCommitter(taskContext);
    } else {
        committer = conf.getOutputCommitter();
    }

	// 为FileOutputFormat设置输出路径
	// FileOutputFormat.getOutputPath(conf)获取mapreduce任务的输出路径
	// FileOutputFormat.setWorkOutputPath()为job设置临时输出目录
    Path outputPath = FileOutputFormat.getOutputPath(conf);
    if (outputPath != null) {
        if ((committer instanceof FileOutputCommitter)) {
            FileOutputFormat.setWorkOutputPath(conf,
                                               ((FileOutputCommitter)committer).getTaskAttemptPath(taskContext));
        } else {
            FileOutputFormat.setWorkOutputPath(conf, outputPath);
        }
    }

	// 为task设置输出的临时目录
	// 若committer的具体实现类型为FileOutputCommitter extends OutputCommitter，setupTask()函数不执行任何操作。
	// task的临时文件目录在task输出数据时才会被创建。 
    committer.setupTask(taskContext);

	// 创建ResourceCalculatorProcessTree，用于获取进程状态信息
    Class<? extends ResourceCalculatorProcessTree> clazz =
        conf.getClass(MRConfig.RESOURCE_CALCULATOR_PROCESS_TREE,
                      null, ResourceCalculatorProcessTree.class);
    pTree = ResourceCalculatorProcessTree
            .getResourceCalculatorProcessTree(System.getenv().get("JVM_PID"), clazz, conf);
    if (pTree != null) {
        pTree.updateProcessTree();
        initCpuCumulativeTime = pTree.getCumulativeCpuTime();
    }
}
```

## normalizeStatus()函数

## updateCounters()函数

## done()函数

```
// 1
TaskUmbilicalProtocol umbilical
LocalJobRunner.Job implement TaskUmbilicalProtocol

```

1. updateCounters()获取FileSystem中的Statisctics对象，并对其中的计数器进行更新。 // ???
2. commitRequired判断是否需要commit。
3. TaskUmbilicalProtocol对象使用hadoop的RPC框架，用于向AM通信，TaskUmbilicalProtocol原本用于向TaskTracker通信。commitPending()用于报告task已经完成，正在等待task的提交操作。LocalJobRunner.Job类是TaskUmbilicalProtocol的一个实现类。
4. commit()函数使用committer提交输出结果。
5. 设置taskStatus，更新计数器，通过TaskUmbilicalProtocol通知task结束。

```
public void done(TaskUmbilicalProtocol umbilical,
                     TaskReporter reporter
                ) throws IOException, InterruptedException {

	// 更新计数器 TO-DO
    updateCounters();

	// 根据task的不同类型及OutputCommitter是否需要commit，commitRequired检查task是否由数据需要commit
    boolean commitRequired = isCommitRequired();

    if (commitRequired) {
        int retries = MAX_RETRIES;

		// 设置task的状态为COMMIT_PENDING
        setState(TaskStatus.State.COMMIT_PENDING);

        while (true) {
            try {
				// task已经完成，正在等待commit
                umbilical.commitPending(taskId, taskStatus);
                break;
            } catch (InterruptedException ie) {
				// ...
            } catch (IOException ie) {
				// ...
            }
        }
		// commit
        commit(umbilical, reporter, committer);
    }
    taskDone.set(true);
    reporter.stopCommunicationThread();
    // Make sure we send at least one set of counter increments. It's
    // ok to call updateCounters() in this thread after comm thread stopped.
    updateCounters();
    sendLastUpdate(umbilical);
    //signal the tasktracker that we are done
    sendDone(umbilical);
}

```

## runJobCleanupTask()函数

```
// 在Job失败时删除临时目录
FileOutputCommitter.abortJob()
// 在Job成功时将task的结果提交到最终输出目录，同时删除临时目录
FileOutputCommitter.commitJob()
```

1. 设置task和progress状态为“cleanup”，更新Job状态信息。
2. 当Job失败时，调用committer.abortJob()函数删除临时目录。
3. 当Job成功时，调用committer.commitJob()函数提交task结果到最终输出目录。
4. 删除Job的.staging目录中的内容。
5. 调用done()函数更新Job的进度状态。

## runJobSetupTask()函数

1. 设置Job状态为“setup”。
2. 调用committer.setupJob()函数设置Job目录，setupJob()函数将为所有的task创建临时根目录。
3. 调用done()函数更新Job的进度状态。

## runTaskCleanupTask()函数

1. 调用taskCleanup()函数。taskCleanup()将设置Task状态为“cleanup”，调用committer.abortTask()函数删除task的工作目录。
2. 调用done()函数更新Job的进度状态。